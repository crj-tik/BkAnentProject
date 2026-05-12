package com.bkanent.business.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/**
 * 员工日工作量实体。
 */
@TableName("employee_daily_workload")
public class EmployeeDailyWorkloadEntity extends BaseEntity {

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
     * 业务属性：statDate。
     */
    private String statDate;
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
     * 业务属性：followUpCount。
     */
    private Integer followUpCount;

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

    public String getStatDate() {
        return statDate;
    }

    public void setStatDate(String statDate) {
        this.statDate = statDate;
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

    public Integer getFollowUpCount() {
        return followUpCount;
    }

    public void setFollowUpCount(Integer followUpCount) {
        this.followUpCount = followUpCount;
    }
}
