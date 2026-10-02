package com.fc.v2;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TItemDeptArchMapper;
import com.fc.v2.mapper.auto.TItemImplementCardMapper;
import com.fc.v2.model.auto.TItemDeptArch;
import com.fc.v2.model.auto.TItemImplementCard;
import com.fc.v2.service.impl.TItemImplementCardServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 实施清单配置卡状态机单测（不连库，mapper 全 Mock）。
 * 逐条对应现场报修：跨档、冻住不拦、退回抹串、重复建卡、名额不减、校验串漂移、旧卡抛错。
 */
@ExtendWith(MockitoExtension.class)
class TItemImplementCardServiceImplTest {

    @Mock
    private TItemImplementCardMapper cardMapper;
    @Mock
    private TItemDeptArchMapper archMapper;

    private TItemImplementCardServiceImpl service;

    @BeforeEach
    void setUp() {
        // ServiceImpl.baseMapper 在泛型父类上，@InjectMocks 注不进去，手工接线
        service = new TItemImplementCardServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", cardMapper);
        ReflectionTestUtils.setField(service, "itemDeptArchMapper", archMapper);
    }

    private TItemImplementCard card(Integer status) {
        TItemImplementCard c = new TItemImplementCard();
        c.setId(100L);
        c.setBillNo("SK-001");
        c.setNodeNo(1);
        c.setSiteId(0);
        c.setSiteNo("ZW00");
        c.setQty(new BigDecimal("300"));
        c.setFineAmt(new BigDecimal("5"));
        c.setStatus(status);
        c.setDelFlag(0);
        c.setGradeLevel(1);
        return c;
    }

    private TItemDeptArch arch() {
        TItemDeptArch a = new TItemDeptArch();
        a.setId(0L);
        a.setSiteNo("ZW00");
        a.setTh1Max(new BigDecimal("300"));
        a.setTh2Max(new BigDecimal("1500"));
        a.setTh3Max(new BigDecimal("6000"));
        a.setStatus(0);
        a.setDelFlag(0);
        return a;
    }

    @SuppressWarnings("unchecked")
    private static String sqlSetOf(ArgumentCaptor<UpdateWrapper> cap) {
        return cap.getValue().getSqlSet();
    }

    @Test
    void advance_goes_one_step_and_writes_checksum() {
        when(cardMapper.selectById(100L)).thenReturn(card(0));
        when(cardMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);

        assertEquals(1, service.advanceTItemImplementCard(100L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper> cap = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(cardMapper).update(any(), cap.capture());
        // 只走一档：0→1；且推进必落校验串
        assertTrue(cap.getValue().getParamNameValuePairs().containsValue(1));
        assertTrue(sqlSetOf(cap).contains("content="));
    }

    @Test
    void advance_blocked_when_frozen() {
        when(cardMapper.selectById(100L)).thenReturn(card(2));
        assertEquals(0, service.advanceTItemImplementCard(100L));
        verify(cardMapper, never()).update(any(), any(UpdateWrapper.class));
    }

    @Test
    void advance_legacy_card_with_null_fields_does_not_throw() {
        TItemImplementCard legacy = card(null);
        legacy.setNodeNo(null);
        legacy.setContent(null);
        legacy.setGradeLevel(null);
        when(cardMapper.selectById(100L)).thenReturn(legacy);
        // nextNodeNo 的 MAX 查询：无兄弟版本 → 版次序补 1
        when(cardMapper.selectOne(any())).thenReturn(new TItemImplementCard());
        when(archMapper.selectOne(any())).thenReturn(arch());
        when(cardMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);

        assertDoesNotThrow(() -> service.advanceTItemImplementCard(100L));
        assertEquals(1, service.advanceTItemImplementCard(100L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper> cap = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(cardMapper, times(2)).update(any(), cap.capture());
        // 旧卡补齐版次序、层级与校验串（看第一次推进的落笔；第二次时版次序已补上）
        String firstSqlSet = cap.getAllValues().get(0).getSqlSet();
        assertTrue(firstSqlSet.contains("node_no="));
        assertTrue(firstSqlSet.contains("grade_level="));
        assertTrue(firstSqlSet.contains("content="));
    }

    @Test
    void revert_only_from_checked_and_keeps_checksum_as_trace() {
        TItemImplementCard checked = card(1);
        checked.setContent("CHECKSUM_A");
        checked.setRemark("旧备注");
        when(cardMapper.selectById(100L)).thenReturn(checked);
        when(cardMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);

        assertEquals(1, service.revertTItemImplementCard(100L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper> cap = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(cardMapper).update(any(), cap.capture());
        // 核验串不抹，旧记录沉入备注留痕
        assertFalse(sqlSetOf(cap).contains("content="));
        assertTrue(sqlSetOf(cap).contains("remark="));
        String remark = "";
        for (Object v : cap.getValue().getParamNameValuePairs().values()) {
            if (v instanceof String && ((String) v).contains("退回留痕")) {
                remark = (String) v;
                break;
            }
        }
        assertTrue(remark.startsWith("旧备注"));
        assertTrue(remark.contains("CHECKSUM_A"));
    }

    @Test
    void revert_blocked_from_pending_and_frozen() {
        when(cardMapper.selectById(100L)).thenReturn(card(0));
        assertEquals(0, service.revertTItemImplementCard(100L));
        when(cardMapper.selectById(100L)).thenReturn(card(2));
        assertEquals(0, service.revertTItemImplementCard(100L));
        verify(cardMapper, never()).update(any(), any(UpdateWrapper.class));
    }

    @Test
    void update_blocked_once_checked_or_frozen() {
        TItemImplementCard edit = new TItemImplementCard();
        edit.setId(100L);
        edit.setContent("HACKED");
        when(cardMapper.selectById(100L)).thenReturn(card(1));
        assertEquals(0, service.updateTItemImplementCard(edit));
        when(cardMapper.selectById(100L)).thenReturn(card(2));
        assertEquals(0, service.updateTItemImplementCard(edit));
        verify(cardMapper, never()).updateById(any());
    }

    @Test
    void update_on_pending_ignores_client_status_and_checksum() {
        when(cardMapper.selectById(100L)).thenReturn(card(0));
        when(archMapper.selectOne(any())).thenReturn(arch());
        when(cardMapper.updateById(any())).thenReturn(1);

        TItemImplementCard edit = new TItemImplementCard();
        edit.setId(100L);
        edit.setQty(new BigDecimal("2000"));
        edit.setStatus(2);          // 客户端想直接冻住
        edit.setContent("HACKED");  // 客户端想自填校验串
        assertEquals(1, service.updateTItemImplementCard(edit));

        ArgumentCaptor<TItemImplementCard> cap = ArgumentCaptor.forClass(TItemImplementCard.class);
        verify(cardMapper).updateById(cap.capture());
        assertEquals(Integer.valueOf(0), cap.getValue().getStatus());
        assertNotEquals("HACKED", cap.getValue().getContent());
        // 2000 件：>1500、≤6000 → 层级 3
        assertEquals(Integer.valueOf(3), cap.getValue().getGradeLevel());
    }

    @Test
    void delete_is_logical_and_frozen_card_refused() {
        when(cardMapper.selectById(100L)).thenReturn(card(2));
        assertEquals(0, service.deleteTItemImplementCardById(100L));

        when(cardMapper.selectById(101L)).thenReturn(card(0));
        when(cardMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);
        assertEquals(1, service.deleteTItemImplementCardById(101L));

        // 全程不物理删行
        verify(cardMapper, never()).deleteById(any(Long.class));
        verify(cardMapper, never()).deleteBatchIds(any());
    }

    @Test
    void insert_rejects_second_running_card_for_same_site_and_bill() {
        when(archMapper.selectOne(any())).thenReturn(arch());
        when(cardMapper.selectCount(any())).thenReturn(1);

        TItemImplementCard rec = new TItemImplementCard();
        rec.setSiteId(0);
        rec.setBillNo("SK-1");
        rec.setQty(new BigDecimal("300"));
        assertEquals(0, service.insertTItemImplementCard(rec));
        verify(cardMapper, never()).insert(any());
    }

    @Test
    void insert_assigns_version_defaults_and_deterministic_checksum() {
        when(archMapper.selectOne(any())).thenReturn(arch());
        when(cardMapper.selectCount(any())).thenReturn(0);
        when(cardMapper.selectOne(any())).thenReturn(new TItemImplementCard());
        when(cardMapper.insert(any())).thenReturn(1);

        TItemImplementCard rec = new TItemImplementCard();
        rec.setSiteId(0);
        rec.setBillNo("SK-1");
        rec.setQty(new BigDecimal("300"));
        rec.setFineAmt(new BigDecimal("5"));
        assertEquals(1, service.insertTItemImplementCard(rec));

        ArgumentCaptor<TItemImplementCard> cap = ArgumentCaptor.forClass(TItemImplementCard.class);
        verify(cardMapper).insert(cap.capture());
        TItemImplementCard saved = cap.getValue();
        assertEquals(Integer.valueOf(0), saved.getStatus());
        assertEquals(Integer.valueOf(1), saved.getNodeNo());
        assertEquals(Integer.valueOf(0), saved.getDelFlag());
        assertEquals(Integer.valueOf(1), saved.getGradeLevel());
        assertNotNull(saved.getContent());
        // 创建者不再拿卡号冒充（交由自动填充落登录名）
        assertNull(saved.getCreateBy());

        // 同要素同版次序重算结果不变：300 与 300.00 归一后同串
        TItemImplementCard rec2 = new TItemImplementCard();
        rec2.setSiteId(0);
        rec2.setBillNo("SK-1");
        rec2.setQty(new BigDecimal("300.00"));
        rec2.setFineAmt(new BigDecimal("5.0"));
        assertEquals(1, service.insertTItemImplementCard(rec2));
        ArgumentCaptor<TItemImplementCard> cap2 = ArgumentCaptor.forClass(TItemImplementCard.class);
        verify(cardMapper, times(2)).insert(cap2.capture());
        assertEquals(saved.getContent(), cap2.getValue().getContent());
    }

    @Test
    void insert_after_previous_frozen_continues_version_order() {
        when(archMapper.selectOne(any())).thenReturn(arch());
        when(cardMapper.selectCount(any())).thenReturn(0);
        TItemImplementCard maxRow = new TItemImplementCard();
        maxRow.setNodeNo(1);
        when(cardMapper.selectOne(any())).thenReturn(maxRow);
        when(cardMapper.insert(any())).thenReturn(1);

        TItemImplementCard rec = new TItemImplementCard();
        rec.setSiteId(0);
        rec.setBillNo("SK-1");
        assertEquals(1, service.insertTItemImplementCard(rec));

        ArgumentCaptor<TItemImplementCard> cap = ArgumentCaptor.forClass(TItemImplementCard.class);
        verify(cardMapper).insert(cap.capture());
        // 前卡已冻住才准续版，版次序接续为 2
        assertEquals(Integer.valueOf(2), cap.getValue().getNodeNo());
    }
}
