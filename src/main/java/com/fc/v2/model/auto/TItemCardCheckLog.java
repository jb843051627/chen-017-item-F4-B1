package com.fc.v2.model.auto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.util.Date;

/**
 * 实施清单配置卡核对留痕对象 t_item_card_check_log
 *
 * <p>每一次“核对通过 / 退回 / 冻住”都在此沉底追加一行，只增不改不删：
 * 退回不再把先前的核对记录抹掉，重核与首核靠 round_no（轮次）与动作时间分出先后。</p>
 *
 * @author fuce
 * @date 2026-10-02
 */
@TableName("t_item_card_check_log")
@ApiModel(value = "TItemCardCheckLog", description = "实施清单配置卡核对留痕")
public class TItemCardCheckLog implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 核对 */
    public static final String ACTION_CHECK = "CHECK";
    /** 退回 */
    public static final String ACTION_REVERT = "REVERT";
    /** 冻住 */
    public static final String ACTION_FREEZE = "FREEZE";
    /** 要素改档（版次序推进） */
    public static final String ACTION_EDIT = "EDIT";

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键")
    private Long id;

    /** 配置卡id */
    @TableField("card_id")
    @ApiModelProperty(value = "配置卡id")
    private Long cardId;

    /** 卡号快照（防止卡本身改号后对不上） */
    @TableField("bill_no")
    @ApiModelProperty(value = "卡号快照")
    private String billNo;

    /** 动作发生时的版次序快照 */
    @TableField("node_no")
    @ApiModelProperty(value = "版次序快照")
    private Integer nodeNo;

    /** 本卡第几轮动作（自 1 起按时间先后递增） */
    @TableField("round_no")
    @ApiModelProperty(value = "轮次")
    private Integer roundNo;

    /** 动作 CHECK/REVERT/FREEZE/EDIT */
    @TableField("action")
    @ApiModelProperty(value = "动作")
    private String action;

    /** 该轮落下时的校验串快照 */
    @TableField("check_sum")
    @ApiModelProperty(value = "校验串快照")
    private String checkSum;

    /** 动作前进展 */
    @TableField("status_before")
    @ApiModelProperty(value = "动作前进展")
    private Integer statusBefore;

    /** 动作后进展 */
    @TableField("status_after")
    @ApiModelProperty(value = "动作后进展")
    private Integer statusAfter;

    /** 经手人 */
    @TableField("operator")
    @ApiModelProperty(value = "经手人")
    private String operator;

    /** 动作时刻 */
    @TableField("create_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "动作时刻")
    private Date createTime;

    /** 备注 */
    @TableField("remark")
    @ApiModelProperty(value = "备注")
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCardId() {
        return cardId;
    }

    public void setCardId(Long cardId) {
        this.cardId = cardId;
    }

    public String getBillNo() {
        return billNo;
    }

    public void setBillNo(String billNo) {
        this.billNo = billNo;
    }

    public Integer getNodeNo() {
        return nodeNo;
    }

    public void setNodeNo(Integer nodeNo) {
        this.nodeNo = nodeNo;
    }

    public Integer getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(Integer roundNo) {
        this.roundNo = roundNo;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getCheckSum() {
        return checkSum;
    }

    public void setCheckSum(String checkSum) {
        this.checkSum = checkSum;
    }

    public Integer getStatusBefore() {
        return statusBefore;
    }

    public void setStatusBefore(Integer statusBefore) {
        this.statusBefore = statusBefore;
    }

    public Integer getStatusAfter() {
        return statusAfter;
    }

    public void setStatusAfter(Integer statusAfter) {
        this.statusAfter = statusAfter;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
