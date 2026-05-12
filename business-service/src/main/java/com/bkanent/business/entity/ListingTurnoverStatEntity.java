package com.bkanent.business.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/**
 * 房源流通效率实体。
 */
@TableName("listing_turnover_stat")
public class ListingTurnoverStatEntity extends BaseEntity {

    /**
     * 业务属性：listingId。
     */
    private Long listingId;
    /**
     * 业务属性：listingTitle。
     */
    private String listingTitle;
    /**
     * 业务属性：storeName。
     */
    private String storeName;
    /**
     * 业务属性：regionName。
     */
    private String regionName;
    /**
     * 业务属性：statMonth。
     */
    private String statMonth;
    /**
     * 业务属性：listingToViewingDays。
     */
    private Integer listingToViewingDays;
    /**
     * 业务属性：viewingToDealDays。
     */
    private Integer viewingToDealDays;
    /**
     * 业务属性：totalTurnoverDays。
     */
    private Integer totalTurnoverDays;
    /**
     * 业务属性：turnoverStatus。
     */
    private String turnoverStatus;

    public Long getListingId() {
        return listingId;
    }

    public void setListingId(Long listingId) {
        this.listingId = listingId;
    }

    public String getListingTitle() {
        return listingTitle;
    }

    public void setListingTitle(String listingTitle) {
        this.listingTitle = listingTitle;
    }

    public String getStoreName() {
        return storeName;
    }

    public void setStoreName(String storeName) {
        this.storeName = storeName;
    }

    public String getRegionName() {
        return regionName;
    }

    public void setRegionName(String regionName) {
        this.regionName = regionName;
    }

    public String getStatMonth() {
        return statMonth;
    }

    public void setStatMonth(String statMonth) {
        this.statMonth = statMonth;
    }

    public Integer getListingToViewingDays() {
        return listingToViewingDays;
    }

    public void setListingToViewingDays(Integer listingToViewingDays) {
        this.listingToViewingDays = listingToViewingDays;
    }

    public Integer getViewingToDealDays() {
        return viewingToDealDays;
    }

    public void setViewingToDealDays(Integer viewingToDealDays) {
        this.viewingToDealDays = viewingToDealDays;
    }

    public Integer getTotalTurnoverDays() {
        return totalTurnoverDays;
    }

    public void setTotalTurnoverDays(Integer totalTurnoverDays) {
        this.totalTurnoverDays = totalTurnoverDays;
    }

    public String getTurnoverStatus() {
        return turnoverStatus;
    }

    public void setTurnoverStatus(String turnoverStatus) {
        this.turnoverStatus = turnoverStatus;
    }
}
