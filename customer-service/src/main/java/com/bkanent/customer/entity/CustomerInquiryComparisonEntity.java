package com.bkanent.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 买方咨询 × 对比报告关联实体，映射 bk_customer.customer_inquiry_comparison。
 * 引用单向（设计决策 2）：只存 reportId 与关联元数据，不复制报告内容；
 * 表无 updated_at/deleted 列，故不继承 {@code BaseEntity}（避免逻辑删除与更新填充列参与 SQL）。
 */
@TableName("customer_inquiry_comparison")
public class CustomerInquiryComparisonEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务属性：id。
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    /**
     * 业务属性：inquiryId（买方咨询 ID，本表内逻辑关联 customer_listing_inquiry）。
     */
    private Long inquiryId;
    /**
     * 业务属性：reportId（对比报告 ID，32 位十六进制，跨库单向引用 listing 域）。
     */
    private String reportId;
    /**
     * 业务属性：createdBy（关联操作人用户 ID）。
     */
    private Long createdBy;
    /**
     * 业务属性：createdAt（关联时间）。
     */
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getInquiryId() {
        return inquiryId;
    }

    public void setInquiryId(Long inquiryId) {
        this.inquiryId = inquiryId;
    }

    public String getReportId() {
        return reportId;
    }

    public void setReportId(String reportId) {
        this.reportId = reportId;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
