package com.bkanent.contract.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.math.BigDecimal;

/**
 * 合同主记录实体。
 */
@TableName("contract_record")
public class ContractEntity extends BaseEntity {

    /**
     * 业务属性：templateId。
     */
    private Long templateId;
    /**
     * 业务属性：contractNo。
     */
    private String contractNo;
    /**
     * 业务属性：title。
     */
    private String title;
    /**
     * 业务属性：contractType。
     */
    private String contractType;
    /**
     * 业务属性：status。
     */
    private String status;
    /**
     * 业务属性：expiryDate。
     */
    private String expiryDate;
    /**
     * 业务属性：brokerId。
     */
    private Long brokerId;
    /**
     * 业务属性：listingId。
     */
    private Long listingId;
    /**
     * 业务属性：customerName。
     */
    private String customerName;
    /**
     * 业务属性：partyAName。
     */
    private String partyAName;
    /**
     * 业务属性：partyBName。
     */
    private String partyBName;
    /**
     * 业务属性：dealAmount。
     */
    private BigDecimal dealAmount;
    /**
     * 业务属性：signedDocumentUrl。
     */
    private String signedDocumentUrl;
    /**
     * 业务属性：externalSealNo。
     */
    private String externalSealNo;
    /**
     * 业务属性：signStartTime。
     */
    private String signStartTime;
    /**
     * 业务属性：bothSignedTime。
     */
    private String bothSignedTime;
    /**
     * 业务属性：archivedTime。
     */
    private String archivedTime;
    /**
     * 业务属性：disputeTime。
     */
    private String disputeTime;
    /**
     * 业务属性：archiveStatus。
     */
    private String archiveStatus;
    /**
     * 业务属性：sealStatus。
     */
    private String sealStatus;
    /**
     * 业务属性：sealProvider。
     */
    private String sealProvider;
    /**
     * 业务属性：sealTime。
     */
    private String sealTime;
    /**
     * 业务属性：ocrSummary。
     */
    private String ocrSummary;
    /**
     * 业务属性：remark。
     */
    private String remark;

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public String getContractNo() {
        return contractNo;
    }

    public void setContractNo(String contractNo) {
        this.contractNo = contractNo;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContractType() {
        return contractType;
    }

    public void setContractType(String contractType) {
        this.contractType = contractType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(String expiryDate) {
        this.expiryDate = expiryDate;
    }

    public Long getBrokerId() {
        return brokerId;
    }

    public void setBrokerId(Long brokerId) {
        this.brokerId = brokerId;
    }

    public Long getListingId() {
        return listingId;
    }

    public void setListingId(Long listingId) {
        this.listingId = listingId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getPartyAName() {
        return partyAName;
    }

    public void setPartyAName(String partyAName) {
        this.partyAName = partyAName;
    }

    public String getPartyBName() {
        return partyBName;
    }

    public void setPartyBName(String partyBName) {
        this.partyBName = partyBName;
    }

    public BigDecimal getDealAmount() {
        return dealAmount;
    }

    public void setDealAmount(BigDecimal dealAmount) {
        this.dealAmount = dealAmount;
    }

    public String getSignedDocumentUrl() {
        return signedDocumentUrl;
    }

    public void setSignedDocumentUrl(String signedDocumentUrl) {
        this.signedDocumentUrl = signedDocumentUrl;
    }

    public String getExternalSealNo() {
        return externalSealNo;
    }

    public void setExternalSealNo(String externalSealNo) {
        this.externalSealNo = externalSealNo;
    }

    public String getSignStartTime() {
        return signStartTime;
    }

    public void setSignStartTime(String signStartTime) {
        this.signStartTime = signStartTime;
    }

    public String getBothSignedTime() {
        return bothSignedTime;
    }

    public void setBothSignedTime(String bothSignedTime) {
        this.bothSignedTime = bothSignedTime;
    }

    public String getArchivedTime() {
        return archivedTime;
    }

    public void setArchivedTime(String archivedTime) {
        this.archivedTime = archivedTime;
    }

    public String getDisputeTime() {
        return disputeTime;
    }

    public void setDisputeTime(String disputeTime) {
        this.disputeTime = disputeTime;
    }

    public String getArchiveStatus() {
        return archiveStatus;
    }

    public void setArchiveStatus(String archiveStatus) {
        this.archiveStatus = archiveStatus;
    }

    public String getSealStatus() {
        return sealStatus;
    }

    public void setSealStatus(String sealStatus) {
        this.sealStatus = sealStatus;
    }

    public String getSealProvider() {
        return sealProvider;
    }

    public void setSealProvider(String sealProvider) {
        this.sealProvider = sealProvider;
    }

    public String getSealTime() {
        return sealTime;
    }

    public void setSealTime(String sealTime) {
        this.sealTime = sealTime;
    }

    public String getOcrSummary() {
        return ocrSummary;
    }

    public void setOcrSummary(String ocrSummary) {
        this.ocrSummary = ocrSummary;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
