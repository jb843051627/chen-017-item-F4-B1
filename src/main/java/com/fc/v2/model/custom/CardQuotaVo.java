package com.fc.v2.model.custom;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;

/**
 * 部门配置卡名额账：界面上数出来的数与底册记的账摆在一处对。
 *
 * @author fuce
 * @date 2026-10-02
 */
@ApiModel(value = "CardQuotaVo", description = "部门配置卡名额账")
public class CardQuotaVo implements Serializable {
    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "底册id")
    private Integer siteId;

    @ApiModelProperty(value = "底册代号")
    private String siteNo;

    @ApiModelProperty(value = "部门全称")
    private String siteName;

    @ApiModelProperty(value = "底册情形 0在编 1已撤并")
    private Integer siteStatus;

    @ApiModelProperty(value = "待核对卡数（屏上数出来的）")
    private Integer pendingCount;

    @ApiModelProperty(value = "已核对卡数（屏上数出来的）")
    private Integer checkedCount;

    @ApiModelProperty(value = "已冻住卡数（屏上数出来的）")
    private Integer frozenCount;

    @ApiModelProperty(value = "底册上记的已冻名额")
    private Integer frozenQuotaUsed;

    @ApiModelProperty(value = "账实是否一致")
    private Boolean reconciled;

    public Integer getSiteId() {
        return siteId;
    }

    public void setSiteId(Integer siteId) {
        this.siteId = siteId;
    }

    public String getSiteNo() {
        return siteNo;
    }

    public void setSiteNo(String siteNo) {
        this.siteNo = siteNo;
    }

    public String getSiteName() {
        return siteName;
    }

    public void setSiteName(String siteName) {
        this.siteName = siteName;
    }

    public Integer getSiteStatus() {
        return siteStatus;
    }

    public void setSiteStatus(Integer siteStatus) {
        this.siteStatus = siteStatus;
    }

    public Integer getPendingCount() {
        return pendingCount;
    }

    public void setPendingCount(Integer pendingCount) {
        this.pendingCount = pendingCount;
    }

    public Integer getCheckedCount() {
        return checkedCount;
    }

    public void setCheckedCount(Integer checkedCount) {
        this.checkedCount = checkedCount;
    }

    public Integer getFrozenCount() {
        return frozenCount;
    }

    public void setFrozenCount(Integer frozenCount) {
        this.frozenCount = frozenCount;
    }

    public Integer getFrozenQuotaUsed() {
        return frozenQuotaUsed;
    }

    public void setFrozenQuotaUsed(Integer frozenQuotaUsed) {
        this.frozenQuotaUsed = frozenQuotaUsed;
    }

    public Boolean getReconciled() {
        return reconciled;
    }

    public void setReconciled(Boolean reconciled) {
        this.reconciled = reconciled;
    }
}
