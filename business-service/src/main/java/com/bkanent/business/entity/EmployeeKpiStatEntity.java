package com.bkanent.business.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.math.BigDecimal;

@TableName("employee_kpi_stat")
public class EmployeeKpiStatEntity extends BaseEntity {

    private Long employeeId;
    private String employeeName;
    private String statMonth;
    private Integer closedDeals;
    private Integer newListings;
    private Integer newCustomers;
    private BigDecimal completionRate;

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

    public String getStatMonth() {
        return statMonth;
    }

    public void setStatMonth(String statMonth) {
        this.statMonth = statMonth;
    }

    public Integer getClosedDeals() {
        return closedDeals;
    }

    public void setClosedDeals(Integer closedDeals) {
        this.closedDeals = closedDeals;
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

    public BigDecimal getCompletionRate() {
        return completionRate;
    }

    public void setCompletionRate(BigDecimal completionRate) {
        this.completionRate = completionRate;
    }
}
