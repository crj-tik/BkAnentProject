package com.bkanent.interview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/**
 * 共性提炼（P2 实现，本期建表占位）。
 */
@TableName("commonality_report")
public class CommonalityReportEntity extends BaseEntity {

    private String assetIds;
    private String profileDoc;
    private String sellingDoc;
    /** TENDENCY|MEDIUM|HIGH（按样本数固定） */
    private String confidence;
    private String individualSignals;

    public String getAssetIds() { return assetIds; }
    public void setAssetIds(String assetIds) { this.assetIds = assetIds; }
    public String getProfileDoc() { return profileDoc; }
    public void setProfileDoc(String profileDoc) { this.profileDoc = profileDoc; }
    public String getSellingDoc() { return sellingDoc; }
    public void setSellingDoc(String sellingDoc) { this.sellingDoc = sellingDoc; }
    public String getConfidence() { return confidence; }
    public void setConfidence(String confidence) { this.confidence = confidence; }
    public String getIndividualSignals() { return individualSignals; }
    public void setIndividualSignals(String individualSignals) { this.individualSignals = individualSignals; }
}
