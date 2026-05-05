package com.bkanent.customer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.math.BigDecimal;

@TableName("customer_profile")
public class CustomerEntity extends BaseEntity {

    private String name;
    private String mobile;
    private String intention;
    private BigDecimal budgetMin;
    private BigDecimal budgetMax;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMobile() {
        return mobile;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile;
    }

    public String getIntention() {
        return intention;
    }

    public void setIntention(String intention) {
        this.intention = intention;
    }

    public BigDecimal getBudgetMin() {
        return budgetMin;
    }

    public void setBudgetMin(BigDecimal budgetMin) {
        this.budgetMin = budgetMin;
    }

    public BigDecimal getBudgetMax() {
        return budgetMax;
    }

    public void setBudgetMax(BigDecimal budgetMax) {
        this.budgetMax = budgetMax;
    }
}
