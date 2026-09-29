package com.bkanent.interview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/**
 * 打法卡（P2 实现，本期建表占位）。
 */
@TableName("playbook_card")
public class PlaybookCardEntity extends BaseEntity {

    private Long assetId;
    private String title;
    /** 原声编号表快照 JSON */
    private String quoteRegistry;
    /** ok|blocked */
    private String constraintStatus;

    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getQuoteRegistry() { return quoteRegistry; }
    public void setQuoteRegistry(String quoteRegistry) { this.quoteRegistry = quoteRegistry; }
    public String getConstraintStatus() { return constraintStatus; }
    public void setConstraintStatus(String constraintStatus) { this.constraintStatus = constraintStatus; }
}
