package com.bkanent.business.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.math.BigDecimal;

/**
 * 员工 KPI 统计实体。
 */
@TableName("employee_kpi_stat")
public class EmployeeKpiStatEntity extends BaseEntity {

    /**
     * 业务属性：employeeId。
     */
    private Long employeeId;
    /**
     * 业务属性：employeeName。
     */
    private String employeeName;
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
     * 业务属性：saleDeals。
     */
    private Integer saleDeals;
    /**
     * 业务属性：rentalDeals。
     */
    private Integer rentalDeals;
    /**
     * 业务属性：closedDeals。
     */
    private Integer closedDeals;
    /**
     * 业务属性：viewingCount。
     */
    private Integer viewingCount;
    /**
     * 业务属性：newListings。
     */
    private Integer newListings;
    /**
     * 业务属性：newCustomers。
     */
    private Integer newCustomers;
    /**
     * 业务属性：privateMessageCount。
     */
    private Integer privateMessageCount;
    /**
     * 业务属性：performanceAmount。
     */
    private BigDecimal performanceAmount;
    /**
     * 业务属性：completionRate。
     */
    private BigDecimal completionRate;
    /**
     * 业务属性：conversionRate。
     */
    private BigDecimal conversionRate;
    /**
     * 业务属性：satisfactionScore。
     */
    private BigDecimal satisfactionScore;

    public Long getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(Long employeeId) {
        this.employeeId = employeeId;
    }

    public String getEmployeeName() {
        return employeeName;
    }

    public void setEmployeeName(String employeeName) {
        this.employeeName = employeeName;
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

    public Integer getSaleDeals() {
        return saleDeals;
    }

    public void setSaleDeals(Integer saleDeals) {
        this.saleDeals = saleDeals;
    }

    public Integer getRentalDeals() {
        return rentalDeals;
    }

    public void setRentalDeals(Integer rentalDeals) {
        this.rentalDeals = rentalDeals;
    }

    public Integer getClosedDeals() {
        return closedDeals;
    }

    public void setClosedDeals(Integer closedDeals) {
        this.closedDeals = closedDeals;
    }

    public Integer getViewingCount() {
        return viewingCount;
    }

    public void setViewingCount(Integer viewingCount) {
        this.viewingCount = viewingCount;
    }

    public Integer getNewListings() {
        return newListings;
    }

    public void setNewListings(Integer newListings) {
        this.newListings = newListings;
    }

    public Integer getNewCustomers() {
        return newCustomers;
    }

    public void setNewCustomers(Integer newCustomers) {
        this.newCustomers = newCustomers;
    }

    public Integer getPrivateMessageCount() {
        return privateMessageCount;
    }

    public void setPrivateMessageCount(Integer privateMessageCount) {
        this.privateMessageCount = privateMessageCount;
    }

    public BigDecimal getPerformanceAmount() {
        return performanceAmount;
    }

    public void setPerformanceAmount(BigDecimal performanceAmount) {
        this.performanceAmount = performanceAmount;
    }

    public BigDecimal getCompletionRate() {
        return completionRate;
    }

    public void setCompletionRate(BigDecimal completionRate) {
        this.completionRate = completionRate;
    }

    public BigDecimal getConversionRate() {
        return conversionRate;
    }

    public void setConversionRate(BigDecimal conversionRate) {
        this.conversionRate = conversionRate;
    }

    public BigDecimal getSatisfactionScore() {
        return satisfactionScore;
    }

    public void setSatisfactionScore(BigDecimal satisfactionScore) {
        this.satisfactionScore = satisfactionScore;
    }
}
