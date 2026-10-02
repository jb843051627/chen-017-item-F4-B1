package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.exception.BizException;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TItemCardCheckLogMapper;
import com.fc.v2.mapper.auto.TItemDeptArchMapper;
import com.fc.v2.mapper.auto.TItemImplementCardMapper;
import com.fc.v2.model.auto.TItemCardCheckLog;
import com.fc.v2.model.auto.TItemDeptArch;
import com.fc.v2.model.auto.TItemImplementCard;
import com.fc.v2.model.custom.CardQuotaVo;
import com.fc.v2.service.ITItemImplementCardService;
import com.fc.v2.util.MD5Util;
import com.fc.v2.util.SnowflakeIdWorker;
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 实施清单配置卡Service业务层处理
 *
 * <p>进展状态机（只许逐档走）：0待核对 → 1已核对 → 2已冻住。
 * 已冻住是终态：不能再推进/退回/改要素/撤卡；只有待核对卡能改要素，只有已核对卡能整档退回。
 * 每次过口都在 t_item_card_check_log 沉底留一行，退回不抹痕；校验串只由要素与版次序决定，
 * 同一版次重核串不变。</p>
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

    /** 正常 */
    private static final long DEL_FLAG_OK = 0L;

    /** 新卡起始版次序 */
    private static final int INIT_NODE_NO = 1;
    /** 新建卡本卡申报年办件量默认值（件/年，尚无实绩按 0 申报） */
    public static final BigDecimal DEFAULT_ANNUAL_QTY = BigDecimal.ZERO;
    /** 底册阈值也没维护时的兜底层级 */
    private static final int DEFAULT_GRADE_LEVEL = 1;

    @Autowired
    private TItemDeptArchMapper itemDeptArchMapper;

    @Autowired
    private TItemCardCheckLogMapper itemCardCheckLogMapper;

    @Override
    public TItemImplementCard selectTItemImplementCardById(Long id) {
        if (id == null) {
            return null;
        }
        TItemImplementCard card = this.baseMapper.selectById(id);
        if (card == null) {
            return null;
        }
        if (card.getDelFlag() != null && card.getDelFlag() != 0) {
            return null;
        }
        // 旧纸档迁入卡字段残缺：只在内存里补齐，看不出来也不抛错
        return normalize(card);
    }

    @Override
    public List<TItemImplementCard> selectTItemImplementCardList(Wrapper<TItemImplementCard> queryWrapper) {
        QueryWrapper<TItemImplementCard> wrapper;
        if (queryWrapper instanceof QueryWrapper) {
            @SuppressWarnings("unchecked")
            QueryWrapper<TItemImplementCard> qw = (QueryWrapper<TItemImplementCard>) queryWrapper;
            wrapper = qw;
        } else {
            wrapper = new QueryWrapper<TItemImplementCard>();
        }
        wrapper.eq("del_flag", DEL_FLAG_OK);
        // 先报的排前头：建卡先后直接看得见，不再靠自然顺序碰运气
        wrapper.orderByAsc("create_time", "id");
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TItemImplementCard insertTItemImplementCard(TItemImplementCard record) {
        if (record == null) {
            throw new BizException("卡内容为空，不能建卡");
        }
        if (record.getSiteId() == null) {
            throw new BizException("未选承办部门底册，不能建卡");
        }
        TItemDeptArch arch = itemDeptArchMapper.selectOne(new QueryWrapper<TItemDeptArch>()
                .eq("id", record.getSiteId()).eq("del_flag", 0));
        if (arch == null) {
            throw new BizException("所选承办部门底册不存在，不能建卡");
        }
        if (arch.getStatus() != null && arch.getStatus() == 1) {
            throw new BizException("该部门底册已撤并，不能再新建配置卡");
        }
        record.setSiteNo(arch.getSiteNo());

        if (StringUtils.isEmpty(record.getBillNo())) {
            record.setBillNo(generateBillNo(arch));
        }

        // 同部门、同事项（卡号）、同一天，两个窗口各报一次也只许立一张
        Date now = new Date();
        LocalDate today = today(now);
        Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TItemImplementCard>()
                .eq("site_id", record.getSiteId())
                .eq("bill_no", record.getBillNo())
                .eq("del_flag", DEL_FLAG_OK)
                .ge("create_time", atStartOfDay(today))
                .lt("create_time", atStartOfDay(today.plusDays(1))));
        if (dupCnt != null && dupCnt > 0) {
            throw new BizException("同一部门名下该事项今天已报过配置卡，不能重复建卡");
        }

        if (record.getQty() == null) {
            record.setQty(DEFAULT_ANNUAL_QTY);
        }
        if (record.getQty().compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("申报年办件量不能为负数");
        }
        // 新卡一律待核对起步，不信前端传的状态
        record.setStatus(STATUS_PENDING);
        record.setNodeNo(INIT_NODE_NO);
        record.setDelFlag(DEL_FLAG_OK);
        record.setGradeLevel(gradeLevelOf(arch, record.getQty()));
        record.setContent(buildCheckSum(record));
        record.setCreateTime(now);
        this.baseMapper.insert(record);
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TItemImplementCard updateTItemImplementCard(TItemImplementCard record) {
        if (record == null || record.getId() == null) {
            throw new BizException("未指明要改的卡");
        }
        TItemImplementCard cur = requireLiveCard(record.getId());

        // 已核对、已冻住的卡一律就地锁死，只有待核对档能改要素
        if (cur.getStatus() != STATUS_PENDING) {
            throw new BizException(cur.getStatus() == STATUS_FROZEN
                    ? "卡已冻住，不能修改" : "卡已核对通过，请先退回待核对再修改");
        }

        boolean materialChanged = false;
        if (record.getQty() != null) {
            if (record.getQty().compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException("申报年办件量不能为负数");
            }
            if (cur.getQty() == null || record.getQty().compareTo(cur.getQty()) != 0) {
                materialChanged = true;
            }
            cur.setQty(record.getQty());
        }
        if (record.getFineAmt() != null && (cur.getFineAmt() == null
                || record.getFineAmt().compareTo(cur.getFineAmt()) != 0)) {
            materialChanged = true;
            cur.setFineAmt(record.getFineAmt());
        }
        if (record.getRemark() != null) {
            cur.setRemark(record.getRemark());
        }
        // 状态、版次序、校验串绝不从请求里接——校验串过了核对还能随手改就是从这漏的
        TItemDeptArch arch = itemDeptArchMapper.selectOne(new QueryWrapper<TItemDeptArch>()
                .eq("id", cur.getSiteId()).eq("del_flag", 0));
        if (arch != null) {
            cur.setGradeLevel(gradeLevelOf(arch, cur.getQty()));
        }

        Integer nodeBefore = cur.getNodeNo();
        if (materialChanged) {
            cur.setNodeNo(nodeBefore + 1);
        }
        cur.setContent(buildCheckSum(cur));
        cur.setUpdateTime(new Date());
        int rows = this.baseMapper.updateById(cur);
        if (rows <= 0) {
            throw new BizException("保存失败，卡可能已被撤掉");
        }
        if (materialChanged) {
            writeLog(cur, TItemCardCheckLog.ACTION_EDIT, nodeBefore, cur.getStatus(), cur.getStatus(),
                    "要素改档，版次序 " + nodeBefore + "→" + cur.getNodeNo());
        }
        return cur;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TItemImplementCard advanceTItemImplementCard(Long id) {
        TItemImplementCard cur = requireLiveCard(id);
        int st = cur.getStatus();
        if (st >= STATUS_FROZEN) {
            throw new BizException("卡已冻住，不能再往前推进");
        }
        // 一次只许走一档：待核对只能到已核对，想跨到冻住必须再推一次
        int next = st + 1;

        persistNormalized(cur);
        String action = next == STATUS_FROZEN ? TItemCardCheckLog.ACTION_FREEZE : TItemCardCheckLog.ACTION_CHECK;
        cur.setStatus(next);
        cur.setUpdateTime(new Date());
        // 串只随版次与要素走：同一版次重核，串与首核完全一致
        cur.setContent(buildCheckSum(cur));
        int rows = this.baseMapper.updateById(cur);
        if (rows <= 0) {
            throw new BizException("推进失败，卡可能已被撤掉");
        }
        writeLog(cur, action, cur.getNodeNo(), st, next, null);

        if (next == STATUS_FROZEN) {
            // 冻住即占名额：卡账与底册账同一笔事务里走，不容许“卡冻了、名额不减/不增”
            int u = itemDeptArchMapper.update(null, new UpdateWrapper<TItemDeptArch>()
                    .eq("id", cur.getSiteId())
                    .setSql("frozen_quota_used = coalesce(frozen_quota_used,0) + 1"));
            if (u <= 0) {
                throw new BizException("底册名额账登记失败，冻住作废");
            }
        }
        return cur;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TItemImplementCard revertTItemImplementCard(Long id) {
        TItemImplementCard cur = requireLiveCard(id);
        int st = cur.getStatus();
        // 待核对无处可退、已冻住根本不许退；只放“已核对→待核对”这一条整档回头路
        if (st == STATUS_PENDING) {
            throw new BizException("卡还在待核对档，没有上一档可退");
        }
        if (st == STATUS_FROZEN) {
            throw new BizException("卡已冻住，不能退回");
        }

        persistNormalized(cur);
        int before = cur.getStatus();
        cur.setStatus(STATUS_PENDING);
        cur.setUpdateTime(new Date());
        // 注意：版次序不动、校验串不抹——先前核对留下的凭据要留得住，重核才比得出新旧
        cur.setContent(buildCheckSum(cur));
        int rows = this.baseMapper.updateById(cur);
        if (rows <= 0) {
            throw new BizException("退回失败，卡可能已被撤掉");
        }
        writeLog(cur, TItemCardCheckLog.ACTION_REVERT, cur.getNodeNo(), before, STATUS_PENDING, null);
        return cur;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteTItemImplementCardByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        if (idArr == null || idArr.length == 0) {
            throw new BizException("未选中要撤的卡");
        }
        // 先逐张验明正身，夹一张冻住卡则整笔拦下
        for (Long id : idArr) {
            TItemImplementCard card = requireLiveCard(id);
            if (card.getStatus() == STATUS_FROZEN) {
                throw new BizException("卡已冻住，不能撤卡");
            }
        }
        int n = 0;
        for (Long id : idArr) {
            n += logicDelete(id);
        }
        return n;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteTItemImplementCardById(Long id) {
        requireLiveCard(id);
        return logicDelete(id);
    }

    @Override
    public List<TItemCardCheckLog> listCheckLogs(Long cardId) {
        return itemCardCheckLogMapper.selectList(new QueryWrapper<TItemCardCheckLog>()
                .eq("card_id", cardId)
                .orderByAsc("round_no", "id"));
    }

    @Override
    public CardQuotaVo quotaOfSite(Integer siteId) {
        if (siteId == null) {
            throw new BizException("未指明部门底册");
        }
        TItemDeptArch arch = itemDeptArchMapper.selectById(siteId);
        if (arch == null) {
            throw new BizException("部门底册不存在");
        }
        return buildQuota(arch);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CardQuotaVo reconcileSite(Integer siteId) {
        if (siteId == null) {
            throw new BizException("未指明部门底册");
        }
        TItemDeptArch arch = itemDeptArchMapper.selectById(siteId);
        if (arch == null) {
            throw new BizException("部门底册不存在");
        }
        CardQuotaVo vo = buildQuota(arch);
        // 以卡实查为准，把底册上记歪了的已冻名额拨正
        itemDeptArchMapper.update(null, new UpdateWrapper<TItemDeptArch>()
                .eq("id", siteId)
                .set("frozen_quota_used", vo.getFrozenCount()));
        vo.setFrozenQuotaUsed(vo.getFrozenCount());
        vo.setReconciled(true);
        return vo;
    }

    // ------------------------------------------------------------------

    /** 取活着的卡；旧纸档迁入的残缺字段（步数/版次序/校验串空着）在内存补齐，绝不因 null 抛错 */
    private TItemImplementCard requireLiveCard(Long id) {
        TItemImplementCard card = this.baseMapper.selectById(id);
        if (card == null) {
            throw new BizException("配置卡不存在");
        }
        if (card.getDelFlag() != null && card.getDelFlag() != 0) {
            throw new BizException("配置卡已撤掉");
        }
        normalize(card);
        return card;
    }

    /** 旧卡兼容：步数、版次序、办件量、删除位为空时按待核对新档看待，不抛 NPE */
    private TItemImplementCard normalize(TItemImplementCard card) {
        if (card.getDelFlag() == null) {
            card.setDelFlag(DEL_FLAG_OK);
        }
        if (card.getStatus() == null) {
            card.setStatus(STATUS_PENDING);
        }
        if (card.getNodeNo() == null) {
            card.setNodeNo(INIT_NODE_NO);
        }
        if (card.getQty() == null) {
            card.setQty(DEFAULT_ANNUAL_QTY);
        }
        if (card.getGradeLevel() == null) {
            card.setGradeLevel(DEFAULT_GRADE_LEVEL);
        }
        return card;
    }

    /** 旧卡第一次被推动时，把内存补齐的默认值与按版次算出的串落库 */
    private void persistNormalized(TItemImplementCard card) {
        TItemDeptArch arch = card.getSiteId() == null ? null : itemDeptArchMapper.selectById(card.getSiteId());
        if (arch != null) {
            card.setSiteNo(arch.getSiteNo());
            card.setGradeLevel(gradeLevelOf(arch, card.getQty()));
        }
        if (StringUtils.isEmpty(card.getContent())) {
            card.setContent(buildCheckSum(card));
        }
    }

    private int logicDelete(Long id) {
        TItemImplementCard card = requireLiveCard(id);
        if (card.getStatus() == STATUS_FROZEN) {
            throw new BizException("卡已冻住，不能撤卡");
        }
        // del_flag 改记本卡 id（非 0 即删）：既软删，又给唯一索引让出位置，将来重报不撞
        return this.baseMapper.update(null, new UpdateWrapper<TItemImplementCard>()
                .eq("id", id)
                .eq("del_flag", DEL_FLAG_OK)
                .setSql("del_flag = id")
                .set("update_time", new Date()));
    }

    private void writeLog(TItemImplementCard ref, String action, Integer nodeSnapshot,
                          int statusBefore, int statusAfter, String remark) {
        TItemCardCheckLog log = new TItemCardCheckLog();
        log.setCardId(ref.getId());
        log.setBillNo(ref.getBillNo());
        log.setNodeNo(nodeSnapshot);
        log.setAction(action);
        log.setCheckSum(ref.getContent());
        log.setStatusBefore(statusBefore);
        log.setStatusAfter(statusAfter);
        log.setRemark(remark);
        log.setCreateTime(new Date());
        Integer maxRound = itemCardCheckLogMapper.selectCount(new QueryWrapper<TItemCardCheckLog>()
                .eq("card_id", ref.getId()));
        log.setRoundNo((maxRound == null ? 0 : maxRound) + 1);
        itemCardCheckLogMapper.insert(log);
    }

    private CardQuotaVo buildQuota(TItemDeptArch arch) {
        CardQuotaVo vo = new CardQuotaVo();
        vo.setSiteId(arch.getId() == null ? null : arch.getId().intValue());
        vo.setSiteNo(arch.getSiteNo());
        vo.setSiteName(arch.getSiteName());
        vo.setSiteStatus(arch.getStatus());
        vo.setPendingCount(countCards(arch.getId(), STATUS_PENDING));
        vo.setCheckedCount(countCards(arch.getId(), STATUS_CHECKED));
        vo.setFrozenCount(countCards(arch.getId(), STATUS_FROZEN));
        vo.setFrozenQuotaUsed(arch.getFrozenQuotaUsed() == null ? 0 : arch.getFrozenQuotaUsed());
        vo.setReconciled(vo.getFrozenCount().equals(vo.getFrozenQuotaUsed()));
        return vo;
    }

    private Integer countCards(Long siteId, int status) {
        Integer c = this.baseMapper.selectCount(new QueryWrapper<TItemImplementCard>()
                .eq("site_id", siteId)
                .eq("status", status)
                .eq("del_flag", DEL_FLAG_OK));
        return c == null ? 0 : c;
    }

    /** 年办件量折服务监督层级：等于上限归入低档（与风险判定线同口径），阈值缺失兜底 1 档 */
    private int gradeLevelOf(TItemDeptArch arch, BigDecimal qty) {
        if (qty == null) {
            return DEFAULT_GRADE_LEVEL;
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
        if (arch.getTh1Max() == null && arch.getTh2Max() == null && arch.getTh3Max() == null) {
            return DEFAULT_GRADE_LEVEL;
        }
        return 4;
    }

    /**
     * 校验串：只取版次身份与本版要素，确定性拼接后 MD5。
     * 不含时间、经手人等易变成分——同一版次退回重核，串必须一字不差；改了要素版次序进位，串才变。
     */
    private String buildCheckSum(TItemImplementCard c) {
        StringBuilder sb = new StringBuilder();
        sb.append(nullToEmpty(c.getBillNo())).append('|');
        sb.append(nullToEmpty(c.getSiteNo())).append('|');
        sb.append(c.getNodeNo() == null ? INIT_NODE_NO : c.getNodeNo()).append('|');
        sb.append(c.getQty() == null ? decimal2(DEFAULT_ANNUAL_QTY) : decimal2(c.getQty())).append('|');
        sb.append(c.getFineAmt() == null ? "" : decimal2(c.getFineAmt())).append('|');
        sb.append(c.getGradeLevel() == null ? "" : c.getGradeLevel());
        return MD5Util.encode(sb.toString());
    }

    private String decimal2(BigDecimal v) {
        return v.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String generateBillNo(TItemDeptArch arch) {
        String day = new java.text.SimpleDateFormat("yyyyMMdd").format(new Date());
        // 雪花后缀，杜绝同日两窗口并发发号撞单（随机4位会撞）
        return "IC" + nullToEmpty(arch.getSiteNo()) + day + SnowflakeIdWorker.getUUID();
    }

    private LocalDate today(Date at) {
        return at.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private Date atStartOfDay(LocalDate day) {
        return Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
