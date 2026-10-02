package com.fc.v2.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.model.auto.TItemCardCheckLog;
import com.fc.v2.model.auto.TItemImplementCard;
import com.fc.v2.model.custom.CardQuotaVo;

import java.util.List;

/**
 * 实施清单配置卡 Service接口
 *
 * <p>进展状态机：0待核对 → 1已核对 → 2已冻住，只许逐档推进、从已核对整档退回。
 * 已冻住即终态：不可推进、不可退回、不可改要素、不可撤卡。</p>
 *
 * @author fuce
 * @date 2026-09-12
 */
public interface ITItemImplementCardService {

    /** 按主键查询（旧卡字段残缺时顺手补齐，不抛错） */
    TItemImplementCard selectTItemImplementCardById(Long id);

    /** 按条件查询列表（仅正常卡，建卡先后排序） */
    List<TItemImplementCard> selectTItemImplementCardList(Wrapper<TItemImplementCard> queryWrapper);

    /** 新建配置卡：默认办件量、分级、同部门同事项同日查重 */
    TItemImplementCard insertTItemImplementCard(TItemImplementCard record);

    /** 改要素：仅待核对卡可改，状态/版次序/校验串不由前端给 */
    TItemImplementCard updateTItemImplementCard(TItemImplementCard record);

    /** 逐档推进（待核对→已核对→已冻住），每步沉底留痕；冻住时占部门名额 */
    TItemImplementCard advanceTItemImplementCard(Long id);

    /** 整档退回（仅已核对→待核对）：留痕保留，不清校验串 */
    TItemImplementCard revertTItemImplementCard(Long id);

    /** 批量逻辑删除（夹一张冻住卡则整笔拦下） */
    int deleteTItemImplementCardByIds(String ids);

    /** 单卡逻辑删除（冻住卡拦下） */
    int deleteTItemImplementCardById(Long id);

    /** 该卡的核对留痕，按动作先后排列 */
    List<TItemCardCheckLog> listCheckLogs(Long cardId);

    /** 部门名额账：屏上各档卡数对底册记的已冻名额 */
    CardQuotaVo quotaOfSite(Integer siteId);

    /** 按卡实重算部门已冻名额，把账拨正 */
    CardQuotaVo reconcileSite(Integer siteId);
}
