package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TItemImplementCardMapper;
import com.fc.v2.mapper.auto.TItemDeptArchMapper;
import com.fc.v2.model.auto.TItemImplementCard;
import com.fc.v2.model.auto.TItemDeptArch;
import com.fc.v2.service.ITItemImplementCardService;
import com.fc.v2.util.MD5Util;
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 实施清单配置卡Service业务层处理
 *
 * 进展状态机：0待核对 → 1已核对 → 2已冻住。
 * <ul>
 *   <li>推进一次只走一档，已冻住为终态——冻住的卡推不得、退不得、改不得、撤不得；</li>
 *   <li>退回只允许 已核对→待核对，核验串不抹除，旧记录沉入备注留痕，前后次序可辨；</li>
 *   <li>校验串由服务端按 卡号|版次序|部门代号|年办件量|承诺时限 确定性生成，
 *       同一版次序重算结果不变，客户端传入的一概不收；</li>
 *   <li>纸质簿迁入的旧卡（进展/版次序/校验串为空）一律按待核对兜底，不再抛错、不再误拦。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TItemImplementCardServiceImpl extends ServiceImpl<TItemImplementCardMapper, TItemImplementCard> implements ITItemImplementCardService {

    /** 进展：待核对 */
    private static final int STATUS_PENDING = 0;
    /** 进展：已核对 */
    private static final int STATUS_CHECKED = 1;
    /** 进展：已冻住（终态） */
    private static final int STATUS_FROZEN = 2;
    /** remark 列长度，留痕超出时从最旧处截 */
    private static final int REMARK_MAX_LEN = 500;

    @Autowired
    private TItemDeptArchMapper itemDeptArchMapper;

    @Override
    public TItemImplementCard selectTItemImplementCardById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<TItemImplementCard>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    @Override
    public List<TItemImplementCard> selectTItemImplementCardList(Wrapper<TItemImplementCard> queryWrapper) {
        // 名录只数正常卡：删除标记在此统一把守，进展不做硬过滤（待核对/已核对/已冻住都要数得出来）；
        // 分页由调用方统一 startPage，这里不再二次分页——否则屏上数出来的与名录记的对不上。
        QueryWrapper<TItemImplementCard> wrapper = new QueryWrapper<TItemImplementCard>();
        wrapper.eq("del_flag", 0);
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertTItemImplementCard(TItemImplementCard record) {
        if (record == null || record.getSiteId() == null || StringUtils.isEmpty(record.getBillNo())) {
            return 0;
        }

        // 锁定部门底册行（FOR UPDATE）：两家窗口同日报同一事项时串行落卡，杜绝一前一后各建一张
        TItemDeptArch refArch = itemDeptArchMapper.selectOne(new QueryWrapper<TItemDeptArch>()
                .eq("id", record.getSiteId()).eq("del_flag", 0).last("FOR UPDATE"));
        if (refArch == null) {
            return 0;
        }
        if (refArch.getStatus() != null && refArch.getStatus() == 1) {
            // 底册已撤并，不再开卡
            return 0;
        }
        record.setSiteNo(refArch.getSiteNo());

        // 同一部门名下同一事项只认一张在办卡：前卡未冻住，后卡不得再建
        Integer runningCnt = this.baseMapper.selectCount(new QueryWrapper<TItemImplementCard>()
                .eq("site_id", record.getSiteId())
                .eq("bill_no", record.getBillNo())
                .eq("del_flag", 0)
                .and(w -> w.ne("status", STATUS_FROZEN).or().isNull("status")));
        if (runningCnt != null && runningCnt > 0) {
            return 0;
        }

        // 版次序由服务端接续递增（前卡冻住才准续版），谁先谁后一目了然
        record.setNodeNo(nextNodeNo(record.getSiteId(), record.getBillNo()));
        // 进展、删除标记、层级、校验串一律由服务端落定，客户端传的不收
        record.setStatus(STATUS_PENDING);
        record.setDelFlag(0);
        record.setGradeLevel(resolveGradeLevel(record.getQty(), refArch));
        record.setContent(buildChecksum(record));
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTItemImplementCard(TItemImplementCard record) {
        if (record == null || record.getId() == null) {
            return 0;
        }
        TItemImplementCard cur = this.baseMapper.selectById(record.getId());
        if (cur == null || isDeleted(cur)) {
            return 0;
        }
        if (statusOf(cur) != STATUS_PENDING) {
            // 已核对/已冻住的卡内容就地改不得（校验串过了核对即锁定）；要改先退回待核对
            return 0;
        }

        // 卡号允许改，但同部门内不得与别的卡撞号
        if (StringUtils.isNotEmpty(record.getBillNo()) && !record.getBillNo().equals(cur.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TItemImplementCard>()
                    .eq("site_id", cur.getSiteId())
                    .eq("bill_no", record.getBillNo())
                    .ne("id", cur.getId())
                    .eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
            cur.setBillNo(record.getBillNo());
        }
        if (record.getQty() != null) {
            cur.setQty(record.getQty());
        }
        if (record.getFineAmt() != null) {
            cur.setFineAmt(record.getFineAmt());
        }
        if (record.getRemark() != null) {
            cur.setRemark(record.getRemark());
        }
        // 进展/版次序/层级/校验串只由服务端维护，客户端传的一概不收
        TItemDeptArch refArch = itemDeptArchMapper.selectOne(new QueryWrapper<TItemDeptArch>()
                .eq("id", cur.getSiteId()).eq("del_flag", 0));
        cur.setGradeLevel(resolveGradeLevel(cur.getQty(), refArch));
        cur.setContent(buildChecksum(cur));
        cur.setUpdateTime(new Date());
        return this.baseMapper.updateById(cur);
    }

    @Override
    public int advanceTItemImplementCard(Long id) {
        if (id == null) {
            return 0;
        }
        TItemImplementCard cur = this.baseMapper.selectById(id);
        if (cur == null || isDeleted(cur)) {
            return 0;
        }
        // 存量旧卡进展为空的一律按待核对兜底，不再抛错
        int st = statusOf(cur);
        if (st >= STATUS_FROZEN) {
            // 已冻住是终态，推不得
            return 0;
        }
        // 一次只走一档：待核对→已核对→已冻住；带上当前进展做更新条件，重复提交不会连跳两档
        int next = st + 1;
        UpdateWrapper<TItemImplementCard> uw = new UpdateWrapper<TItemImplementCard>()
                .eq("id", id).eq("del_flag", 0);
        if (cur.getStatus() == null) {
            uw.isNull("status");
        } else {
            uw.eq("status", st);
        }
        uw.set("status", next);
        // 旧卡版次序为空的先补齐，再按本版重算校验串——推进必留核验串
        Integer nodeNo = cur.getNodeNo();
        if (nodeNo == null) {
            nodeNo = nextNodeNo(cur.getSiteId(), cur.getBillNo());
            uw.set("node_no", nodeNo);
        }
        cur.setNodeNo(nodeNo);
        if (cur.getGradeLevel() == null) {
            TItemDeptArch refArch = itemDeptArchMapper.selectOne(new QueryWrapper<TItemDeptArch>()
                    .eq("id", cur.getSiteId()).eq("del_flag", 0));
            Integer grade = resolveGradeLevel(cur.getQty(), refArch);
            if (grade != null) {
                uw.set("grade_level", grade);
            }
        }
        uw.set("content", buildChecksum(cur));
        uw.set("update_time", new Date());
        return this.baseMapper.update(null, uw);
    }

    @Override
    public int revertTItemImplementCard(Long id) {
        if (id == null) {
            return 0;
        }
        TItemImplementCard cur = this.baseMapper.selectById(id);
        if (cur == null || isDeleted(cur)) {
            return 0;
        }
        // 只有已核对才能退回待核对：待核对无处可退，已冻住是终态退不得
        if (statusOf(cur) != STATUS_CHECKED) {
            return 0;
        }
        UpdateWrapper<TItemImplementCard> uw = new UpdateWrapper<TItemImplementCard>()
                .eq("id", id).eq("del_flag", 0).eq("status", STATUS_CHECKED);
        uw.set("status", STATUS_PENDING);
        // 核验串不抹：本版校验串原样保留，旧记录沉入备注留痕，重核与首核前后可辨
        uw.set("remark", appendRevertTrace(cur));
        uw.set("update_time", new Date());
        return this.baseMapper.update(null, uw);
    }

    @Override
    public int deleteTItemImplementCardByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        if (idArr == null || idArr.length == 0) {
            return 0;
        }
        int deleted = 0;
        for (TItemImplementCard card : this.baseMapper.selectBatchIds(Arrays.asList(idArr))) {
            // 已冻住的卡撤不得，逐张把守；其余逻辑删除
            deleted += deleteTItemImplementCardById(card.getId());
        }
        return deleted;
    }

    @Override
    public int deleteTItemImplementCardById(Long id) {
        if (id == null) {
            return 0;
        }
        TItemImplementCard cur = this.baseMapper.selectById(id);
        if (cur == null || isDeleted(cur)) {
            return 0;
        }
        if (statusOf(cur) == STATUS_FROZEN) {
            // 已冻住的卡撤不得
            return 0;
        }
        // 逻辑删除：名额从名录上销掉，不物理抹行
        UpdateWrapper<TItemImplementCard> uw = new UpdateWrapper<TItemImplementCard>()
                .eq("id", id).eq("del_flag", 0);
        uw.set("del_flag", 1);
        uw.set("update_time", new Date());
        return this.baseMapper.update(null, uw);
    }

    /** 删除标记兜底：旧卡 del_flag 为空按正常计，不再被当成已删/已停误拦 */
    private static boolean isDeleted(TItemImplementCard card) {
        return card.getDelFlag() != null && card.getDelFlag() == 1;
    }

    /** 进展兜底：旧卡进展为空按待核对计 */
    private static int statusOf(TItemImplementCard card) {
        return card.getStatus() == null ? STATUS_PENDING : card.getStatus();
    }

    /** 下一版次序：同部门同事项现有最大版次序 + 1，首卡为 1 */
    private int nextNodeNo(Integer siteId, String billNo) {
        TItemImplementCard row = this.baseMapper.selectOne(new QueryWrapper<TItemImplementCard>()
                .select("IFNULL(MAX(node_no), 0) AS node_no")
                .eq("site_id", siteId)
                .eq("bill_no", billNo)
                .eq("del_flag", 0));
        return (row == null || row.getNodeNo() == null ? 0 : row.getNodeNo()) + 1;
    }

    /**
     * 校验串随版次序走：同一张卡同一版次序，只要申报要素不变，重算结果不变，
     * 退回重核后与首次核对新旧对得齐。
     */
    private String buildChecksum(TItemImplementCard card) {
        String raw = StringUtils.defaultString(card.getBillNo()) + "|"
                + (card.getNodeNo() == null ? "" : card.getNodeNo().toString()) + "|"
                + StringUtils.defaultString(card.getSiteNo()) + "|"
                + decimal(card.getQty()) + "|"
                + decimal(card.getFineAmt());
        return MD5Util.encode(raw);
    }

    /** 年办件量折服务监督层级：≤低界→1，≤中界→2，≤高界→3，超高界→4；量空或三线全空则层级空 */
    private static Integer resolveGradeLevel(BigDecimal qty, TItemDeptArch arch) {
        if (qty == null || arch == null
                || (arch.getTh1Max() == null && arch.getTh2Max() == null && arch.getTh3Max() == null)) {
            return null;
        }
        if (arch.getTh1Max() != null && qty.compareTo(arch.getTh1Max()) <= 0) {
            return 1;
        }
        if (arch.getTh2Max() != null && qty.compareTo(arch.getTh2Max()) <= 0) {
            return 2;
        }
        if (arch.getTh3Max() != null && qty.compareTo(arch.getTh3Max()) <= 0) {
            return 3;
        }
        return 4;
    }

    /** 退回留痕：旧核验串沉底备注，重核次序可辨；超出列长从最旧处截 */
    private static String appendRevertTrace(TItemImplementCard cur) {
        String trace = "[退回留痕]版次序" + (cur.getNodeNo() == null ? "-" : cur.getNodeNo())
                + " 校验串" + (cur.getContent() == null ? "-" : cur.getContent())
                + " " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String old = cur.getRemark();
        String joined = StringUtils.isEmpty(old) ? trace : old + "\n" + trace;
        return joined.length() <= REMARK_MAX_LEN ? joined : joined.substring(joined.length() - REMARK_MAX_LEN);
    }

    private static String decimal(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }
}
