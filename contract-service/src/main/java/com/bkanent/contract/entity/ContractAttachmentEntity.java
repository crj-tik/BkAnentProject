package com.bkanent.contract.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/**
 * 合同附件实体。
 */
@TableName("contract_attachment")
public class ContractAttachmentEntity extends BaseEntity {

    /**
     * 业务属性：contractId。
     */
    private Long contractId;
    /**
     * 业务属性：attachmentType。
     */
    private String attachmentType;
    /**
     * 业务属性：fileName。
     */
    private String fileName;
    /**
     * 业务属性：fileUrl。
     */
    private String fileUrl;
    /**
     * 业务属性：ocrStatus。
     */
    private String ocrStatus;
    /**
     * 业务属性：ocrText。
     */
    private String ocrText;
    /**
     * 业务属性：ocrStructuredData。
     */
    private String ocrStructuredData;
    /**
     * 业务属性：ocrProvider。
     */
    private String ocrProvider;
    /**
     * 业务属性：ocrTime。
     */
    private String ocrTime;
    /**
     * 业务属性：remark。
     */
    private String remark;

    public Long getContractId() {
        return contractId;
    }

    public void setContractId(Long contractId) {
        this.contractId = contractId;
    }

    public String getAttachmentType() {
        return attachmentType;
    }

    public void setAttachmentType(String attachmentType) {
        this.attachmentType = attachmentType;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }

    public String getOcrStatus() {
        return ocrStatus;
    }

    public void setOcrStatus(String ocrStatus) {
        this.ocrStatus = ocrStatus;
    }

    public String getOcrText() {
        return ocrText;
    }

    public void setOcrText(String ocrText) {
        this.ocrText = ocrText;
    }

    public String getOcrStructuredData() {
        return ocrStructuredData;
    }

    public void setOcrStructuredData(String ocrStructuredData) {
        this.ocrStructuredData = ocrStructuredData;
    }

    public String getOcrProvider() {
        return ocrProvider;
    }

    public void setOcrProvider(String ocrProvider) {
        this.ocrProvider = ocrProvider;
    }

    public String getOcrTime() {
        return ocrTime;
    }

    public void setOcrTime(String ocrTime) {
        this.ocrTime = ocrTime;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
