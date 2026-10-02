package com.fc.v2.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.common.exception.BizException;
import com.fc.v2.mapper.auto.TItemCardCheckLogMapper;
import com.fc.v2.mapper.auto.TItemDeptArchMapper;
import com.fc.v2.mapper.auto.TItemImplementCardMapper;
import com.fc.v2.model.auto.TItemCardCheckLog;
import com.fc.v2.model.auto.TItemDeptArch;
import com.fc.v2.model.auto.TItemImplementCard;
import com.fc.v2.model.custom.CardQuotaVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 实施清单配置卡服务无库单测（纯 Mockito）：
 * 复现路径=新建卡（默认办件量）→往前走一步，逐项验证状态机守卫、留痕、
 * 查重、旧卡兼容、名额账与校验串稳定性。
 */
public class TItemImplementCardServiceImplTest {

    private TItemImplementCardServiceImpl service;
    private TItemImplementCardMapper cardMapper;
    private TItemDeptArchMapper archMapper;
    private TItemCardCheckLogMapper logMapper;

    private TItemDeptArch activeArch;
    private List<TItemCardCheckLog> logs;

    @BeforeEach
    public void setUp() {
        service = new TItemImplementCardServiceImpl();
        cardMapper = mock(TItemImplementCardMapper.class);
        archMapper = mock(TItemDeptArchMapper.class);
        logMapper = mock(TItemCardCheckLogMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", cardMapper);
        ReflectionTestUtils.setField(service, "itemDeptArchMapper", archMapper);
        ReflectionTestUtils.setField(service, "itemCardCheckLogMapper", logMapper);

        activeArch = new TItemDeptArch();
        activeArch.setId(0L);
        activeArch.setSiteNo("ZW00");
        activeArch.setSiteName("在编底册");
        activeArch.setStatus(0);
        activeArch.setDelFlag(0);
        activeArch.setTh1Max(new BigDecimal("300"));
        activeArch.setTh2Max(new BigDecimal("1500"));
        activeArch.setTh3Max(new BigDecimal("6000"));
        activeArch.setFrozenQuotaUsed(0);

        logs = new ArrayList<>();
        when(cardMapper.insert(any())).thenReturn(1);
        when(cardMapper.updateById(any())).thenReturn(1);
        when(cardMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(archMapper.selectOne(any(Wrapper.class))).thenReturn(activeArch);
        when(archMapper.selectById(any())).thenReturn(activeArch);
        when(archMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(logMapper.insert(any())).thenAnswer(inv -> {
            logs.add(inv.getArgument(0));
            return 1;
        });
        when(logMapper.selectCount(any(Wrapper.class))).thenAnswer(
                inv -> (int) logs.stream().filter(l -> true).count());
    }

    private void stubCard(TItemImplementCard card) {
        when(cardMapper.selectById(card.getId())).thenReturn(card);
    }

    private TItemImplementCard newCard() {
        TItemImplementCard c = new TItemImplementCard();
        c.setId(1001L);
        c.setBillNo("IC-ZW00-事项A");
        c.setSiteId(0);
        c.setSiteNo("ZW00");
        c.setStatus(0);
        c.setNodeNo(1);
        c.setQty(BigDecimal.ZERO);
        c.setDelFlag(0L);
        return c;
    }

    // ---- 复现路径：新建（默认值）→ 推一步 ----

    @Test
    public void insertDefaults_qtyZero_statusPending_checksumAndGradeFilled() {
        TItemImplementCard form = new TItemImplementCard();
        form.setSiteId(0);
        form.setBillNo("IC-ZW00-事项A");
        // 办件量留空 → 走默认值；当天无重卡
        when(cardMapper.selectCount(any(Wrapper.class))).thenAnswer(inv -> {
            String sql = ((Wrapper<?>) inv.getArgument(0)).getSqlSegment();
            return sql.contains("create_time") ? 0 : 0;
        });

        TItemImplementCard saved = service.insertTItemImplementCard(form);

        assertEquals(0, saved.getQty().compareTo(BigDecimal.ZERO), "办件量应取默认 0");
        assertEquals(0, saved.getStatus(), "新卡必须待核对起步");
        assertEquals(1, saved.getNodeNo());
        assertEquals(0L, saved.getDelFlag());
        assertEquals(1, saved.getGradeLevel(), "0 件落第 1 监督层级");
        assertNotNull(saved.getContent(), "建卡即应生成校验串");
        assertEquals(32, saved.getContent().length(), "校验串为 MD5");
        assertEquals("ZW00", saved.getSiteNo());
        verify(cardMapper).insert(saved);
    }

    @Test
    public void advanceFromPending_goesExactlyOneStep_writesCheckLog_noQuotaYet() {
        TItemImplementCard card = newCard();
        stubCard(card);

        TItemImplementCard after = service.advanceTItemImplementCard(1001L);

        assertEquals(1, after.getStatus(), "一推只许到已核对，不能跨到冻住");
        assertEquals(1, logs.size());
        assertEquals(TItemCardCheckLog.ACTION_CHECK, logs.get(0).getAction());
        assertEquals(1, logs.get(0).getRoundNo());
        assertEquals(0, logs.get(0).getStatusBefore());
        assertEquals(1, logs.get(0).getStatusAfter());
        // 只推到已核对：底册名额账不动
        verify(archMapper, never()).update(isNull(), any(Wrapper.class));
    }

    // ---- 坑1：跳态/终态守卫 ----

    @Test
    public void advanceFromFrozen_isBlocked() {
        TItemImplementCard card = newCard();
        card.setStatus(2);
        stubCard(card);

        BizException ex = assertThrows(BizException.class, () -> service.advanceTItemImplementCard(1001L));
        assertTrue(ex.getMessage().contains("冻住"));
        verify(cardMapper, never()).updateById(any());
    }

    @Test
    public void fullFlow_secondAdvanceFreezes_andConsumesOneQuota() {
        TItemImplementCard card = newCard();
        card.setStatus(1);
        stubCard(card);

        TItemImplementCard after = service.advanceTItemImplementCard(1001L);

        assertEquals(2, after.getStatus());
        assertEquals(1, logs.size());
        assertEquals(TItemCardCheckLog.ACTION_FREEZE, logs.get(0).getAction());
        // 冻住同一事务里占一个名额
        verify(archMapper, times(1)).update(isNull(), any(Wrapper.class));
    }

    // ---- 坑2/3：冻住卡不能退、不能改、不能撤；待核对不能退 ----

    @Test
    public void revert_isBlockedForPendingAndFrozen() {
        TItemImplementCard pending = newCard();
        stubCard(pending);
        BizException e1 = assertThrows(BizException.class, () -> service.revertTItemImplementCard(1001L));
        assertTrue(e1.getMessage().contains("没有上一档"));

        TItemImplementCard frozen = newCard();
        frozen.setId(1002L);
        frozen.setStatus(2);
        when(cardMapper.selectById(1002L)).thenReturn(frozen);
        BizException e2 = assertThrows(BizException.class, () -> service.revertTItemImplementCard(1002L));
        assertTrue(e2.getMessage().contains("冻住"));
        verify(cardMapper, never()).updateById(any());
    }

    @Test
    public void edit_isBlockedForCheckedAndFrozen() {
        TItemImplementCard checked = newCard();
        checked.setStatus(1);
        stubCard(checked);
        TItemImplementCard form = new TItemImplementCard();
        form.setId(1001L);
        form.setQty(new BigDecimal("100"));
        BizException e = assertThrows(BizException.class, () -> service.updateTItemImplementCard(form));
        assertTrue(e.getMessage().contains("退回待核对"));

        TItemImplementCard frozen = newCard();
        frozen.setId(1003L);
        frozen.setStatus(2);
        when(cardMapper.selectById(1003L)).thenReturn(frozen);
        form.setId(1003L);
        BizException e2 = assertThrows(BizException.class, () -> service.updateTItemImplementCard(form));
        assertTrue(e2.getMessage().contains("冻住"));
    }

    @Test
    public void deleteFrozenCard_isBlocked_andBatchAborts() {
        TItemImplementCard frozen = newCard();
        frozen.setId(2001L);
        frozen.setStatus(2);
        when(cardMapper.selectById(2001L)).thenReturn(frozen);

        assertThrows(BizException.class, () -> service.deleteTItemImplementCardById(2001L));
        assertThrows(BizException.class, () -> service.deleteTItemImplementCardByIds("2001"));
        // 一次软删 update 都不该发生
        verify(cardMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    public void deletePendingCard_isLogicalDelete() {
        TItemImplementCard card = newCard();
        stubCard(card);
        int n = service.deleteTItemImplementCardById(1001L);
        assertEquals(1, n);
        // 走软删（update del_flag=id），不再物理 delete
        verify(cardMapper).update(isNull(), any(Wrapper.class));
        verify(cardMapper, never()).deleteById(anyLong());
    }

    // ---- 坑5：退回留痕保留；坑4：校验串同版稳定、要素改版进位 ----

    @Test
    public void checksumStableAcrossRevertAndRecheck_nodeAndSumKept() {
        TItemImplementCard card = newCard();
        stubCard(card);

        service.advanceTItemImplementCard(1001L);
        String sumAfterCheck = card.getContent();
        assertEquals(1, card.getNodeNo());

        service.revertTItemImplementCard(1001L);
        assertEquals(0, card.getStatus(), "退回到待核对");
        assertEquals(1, card.getNodeNo(), "退回不动版次序");
        assertEquals(sumAfterCheck, card.getContent(), "退回不抹校验串");
        assertEquals(2, logs.size(), "留痕追加，不抹旧记录");
        assertEquals(TItemCardCheckLog.ACTION_REVERT, logs.get(1).getAction());
        assertEquals(2, logs.get(1).getRoundNo(), "轮次递增，先后可辨");

        service.advanceTItemImplementCard(1001L);
        assertEquals(sumAfterCheck, card.getContent(), "同一版次重核，串必须与首核一字不差");
        assertEquals(3, logs.size(), "重核是新的一轮，与首核分得清");
    }

    @Test
    public void editPendingMaterial_bumpsNodeAndChangesSum_ignoresClientStatusAndSum() {
        TItemImplementCard card = newCard();
        stubCard(card);
        String oldSum = card.getContent();

        TItemImplementCard form = new TItemImplementCard();
        form.setId(1001L);
        form.setQty(new BigDecimal("1000"));
        // 前端伪造状态/校验串：服务端绝不能接
        form.setStatus(2);
        form.setContent("hacked-sum");

        TItemImplementCard saved = service.updateTItemImplementCard(form);

        assertEquals(0, saved.getStatus(), "状态不由请求决定，仍在待核对");
        assertNotEquals("hacked-sum", saved.getContent(), "校验串不能由请求改写");
        assertEquals(2, saved.getNodeNo(), "要素改档，版次序进位");
        assertNotEquals(oldSum, saved.getContent(), "要素变了，串应随之变");
        assertEquals(2, saved.getGradeLevel(), "1000 件过 300 中界线落第 2 层");
        assertEquals(1, logs.size());
        assertEquals(TItemCardCheckLog.ACTION_EDIT, logs.get(0).getAction());
    }

    // ---- 坑3：同部门同事项同日查重（两窗口各报一次） ----

    @Test
    public void duplicateSameDeptSameItemSameDay_isRejected() {
        when(cardMapper.selectCount(any(Wrapper.class))).thenAnswer(inv -> {
            String sql = ((Wrapper<?>) inv.getArgument(0)).getSqlSegment();
            // 建卡查重的 wrapper 带 create_time 当日窗口
            return sql.contains("create_time") ? 1 : 0;
        });

        TItemImplementCard form = new TItemImplementCard();
        form.setSiteId(0);
        form.setBillNo("IC-ZW00-事项A");
        BizException e = assertThrows(BizException.class, () -> service.insertTItemImplementCard(form));
        assertTrue(e.getMessage().contains("已报过"));
        verify(cardMapper, never()).insert(any());
    }

    @Test
    public void insertOnMergedArch_isRejected() {
        activeArch.setStatus(1);
        TItemImplementCard form = new TItemImplementCard();
        form.setSiteId(0);
        form.setBillNo("IC-ZW00-X");
        BizException e = assertThrows(BizException.class, () -> service.insertTItemImplementCard(form));
        assertTrue(e.getMessage().contains("撤并"));
        verify(cardMapper, never()).insert(any());
    }

    // ---- 坑6：旧纸档迁入卡字段全空，不抛错、不误拦 ----

    @Test
    public void legacyCardWithNulls_isNormalizedAndAdvanceable_evenIfArchMissing() {
        TItemImplementCard legacy = new TItemImplementCard();
        legacy.setId(3001L);
        legacy.setBillNo("旧卡");
        legacy.setSiteId(99);
        // status/nodeNo/qty/content/delFlag 全空
        when(cardMapper.selectById(3001L)).thenReturn(legacy);
        when(archMapper.selectById(99)).thenReturn(null); // 底册也查不到，不该误判成“已停”拦死

        TItemImplementCard view = service.selectTItemImplementCardById(3001L);
        assertEquals(0, view.getStatus());
        assertEquals(1, view.getNodeNo());
        assertEquals(0, view.getQty().compareTo(BigDecimal.ZERO));
        assertEquals(0L, view.getDelFlag());

        TItemImplementCard after = assertDoesNotThrow(() -> service.advanceTItemImplementCard(3001L));
        assertEquals(1, after.getStatus());
        assertNotNull(after.getContent(), "旧串缺失，推动时自动补算");
        assertEquals(1, logs.size());
    }

    // ---- 坑7：名录名额账与冻住步调一致、可拨正 ----

    @SuppressWarnings("unchecked")
    @Test
    public void quota_countsByStatus_andReconcileRepairsLedger() {
        activeArch.setFrozenQuotaUsed(9); // 底册账记歪了
        when(cardMapper.selectCount(any(Wrapper.class))).thenAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> w =
                    (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) inv.getArgument(0);
            // 渲染 SQL 片段后参数映射才填齐；countCards 顺序固定 site_id/status/del_flag
            w.getSqlSegment();
            Object status = w.getParamNameValuePairs().get("MPGENVAL2");
            switch (((Number) status).intValue()) {
                case 0: return 3;
                case 1: return 5;
                case 2: return 2;
                default: return 0;
            }
        });
        when(archMapper.selectById(0)).thenReturn(activeArch);

        CardQuotaVo vo = service.quotaOfSite(0);
        assertEquals(3, vo.getPendingCount());
        assertEquals(5, vo.getCheckedCount());
        assertEquals(2, vo.getFrozenCount(), "屏上数出来 2 张冻住");
        assertEquals(9, vo.getFrozenQuotaUsed(), "底册记 9");
        assertFalse(vo.getReconciled(), "账实不符要亮出来");

        CardQuotaVo fixed = service.reconcileSite(0);
        assertTrue(fixed.getReconciled());
        assertEquals(2, fixed.getFrozenQuotaUsed(), "按卡实拨正为 2");

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(archMapper).update(isNull(), cap.capture());
        assertTrue(cap.getValue().getSqlSet().contains("frozen_quota_used"),
                "应把底册已冻名额重写为卡实数");
    }

    // ---- 列表按建卡先后排序 ----

    @Test
    public void list_onlyLiveCards_orderedByCreateTimeThenId() {
        List<TItemImplementCard> db = new ArrayList<>();
        db.add(newCard());
        when(cardMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> {
            Wrapper<?> w = inv.getArgument(0);
            assertTrue(w.getSqlSegment().contains("del_flag"), "只查正常卡");
            assertTrue(w.getSqlSegment().toLowerCase().contains("order by"), "必须显式按建卡先后排序");
            return db;
        });

        List<TItemImplementCard> list = service.selectTItemImplementCardList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
        assertEquals(1, list.size());
    }
}
