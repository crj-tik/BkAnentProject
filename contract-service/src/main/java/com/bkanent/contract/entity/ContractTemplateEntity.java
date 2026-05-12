package com.bkanent.contract.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/**
 * 合同模板实体。
 */
@TableName("contract_template")
public class ContractTemplateEntity extends BaseEntity {

    /**
     * 业务属性：templateCode。
     */
    private String templateCode;
    /**
     * 业务属性：templateName。
     */
    private String templateName;
    /**
     * 业务属性：contractType。
     */
    private String contractType;
    /**
     * 业务属性：versionNo。
     */
    private Integer versionNo;
    /**
     * 业务属性：templateContent。
     */
    private String templateContent;
    /**
     * 业务属性：templateFileUrl。
     */
    private String templateFileUrl;
    /**
     * 业务属性：status。
     */
    private String status;
    /**
     * 业务属性：remark。
     */
    private String remark;

    public String getTemplateCode() {
        return templateCode;
    }

    public void setTemplateCode(String templateCode) {
        this.templateCode = templateCode;
    }

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }

    public String getContractType() {
        return contractType;
    }

    public void setContractType(String contractType) {
        this.contractType = contractType;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getTemplateContent() {
        return templateContent;
    }

    public void setTemplateContent(String templateContent) {
        this.templateContent = templateContent;
    }

    public String getTemplateFileUrl() {
        return templateFileUrl;
    }

    public void setTemplateFileUrl(String templateFileUrl) {
        this.templateFileUrl = templateFileUrl;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
