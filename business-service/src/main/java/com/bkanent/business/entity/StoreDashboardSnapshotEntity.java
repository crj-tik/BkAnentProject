package com.bkanent.business.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.math.BigDecimal;

/**
 * 门店经营仪表盘快照实体。
 */
@TableName("store_dashboard_snapshot")
public class StoreDashboardSnapshotEntity extends BaseEntity {

    /**
     * 业务属性：storeName。
     */
    private String storeName;
    /**
     * 业务属性：regionName。
     */
    private String regionName;
    /**
     * 业务属性：statDate。
     */
    private String statDate;
    /**
     * 业务属性：activeListingCount。
     */
    private Integer activeListingCount;
    /**
     * 业务属性：todayViewingCount。
     */
    private Integer todayViewingCount;
    /**
     * 业务属性：todayNewCustomerCount。
     */
    private Integer todayNewCustomerCount;
    /**
     * 业务属性：todayDealCount。
     */
    private Integer todayDealCount;
    /**
     * 业务属性：todayPerformanceAmount。
     */
    private BigDecimal todayPerformanceAmount;
    /**
     * 业务属性：satisfactionScore。
     */
    private BigDecimal satisfactionScore;

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

    public String getStatDate() {
        return statDate;
    }

    public void setStatDate(String statDate) {
        this.statDate = statDate;
    }

    public Integer getActiveListingCount() {
        return activeListingCount;
    }

    public void setActiveListingCount(Integer activeListingCount) {
        this.activeListingCount = activeListingCount;
    }

    public Integer getTodayViewingCount() {
        return todayViewingCount;
    }

    public void setTodayViewingCount(Integer todayViewingCount) {
        this.todayViewingCount = todayViewingCount;
    }

    public Integer getTodayNewCustomerCount() {
        return todayNewCustomerCount;
    }

    public void setTodayNewCustomerCount(Integer todayNewCustomerCount) {
        this.todayNewCustomerCount = todayNewCustomerCount;
    }

    public Integer getTodayDealCount() {
        return todayDealCount;
    }

    public void setTodayDealCount(Integer todayDealCount) {
        this.todayDealCount = todayDealCount;
    }

    public BigDecimal getTodayPerformanceAmount() {
        return todayPerformanceAmount;
    }

    public void setTodayPerformanceAmount(BigDecimal todayPerformanceAmount) {
        this.todayPerformanceAmount = todayPerformanceAmount;
    }

    public BigDecimal getSatisfactionScore() {
        return satisfactionScore;
    }

    public void setSatisfactionScore(BigDecimal satisfactionScore) {
        this.satisfactionScore = satisfactionScore;
    }
}
