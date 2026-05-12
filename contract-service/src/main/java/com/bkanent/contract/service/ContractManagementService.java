package com.bkanent.contract.service;

import com.bkanent.contract.model.ContractArchiveRequest;
import com.bkanent.contract.model.ContractAttachmentOcrRequest;
import com.bkanent.contract.model.ContractAttachmentResponse;
import com.bkanent.contract.model.ContractDetailResponse;
import com.bkanent.contract.model.ContractSealRequest;
import com.bkanent.contract.model.ContractStatusUpdateRequest;
import com.bkanent.contract.model.ContractTemplateResponse;
import com.bkanent.contract.model.ContractTemplateUpsertRequest;
import com.bkanent.contract.model.ContractUpsertRequest;

import java.util.List;

/**
 * 合同管理服务接口。
 */
public interface ContractManagementService {

    /**
     * 业务方法：saveTemplate。
     */
    ContractTemplateResponse saveTemplate(ContractTemplateUpsertRequest request);

    /**
     * 业务方法：listTemplates。
     */
    List<ContractTemplateResponse> listTemplates(String contractType);

    /**
     * 业务方法：saveContract。
     */
    ContractDetailResponse saveContract(ContractUpsertRequest request);

    /**
     * 业务方法：listContracts。
     */
    List<ContractDetailResponse> listContracts(String contractType, String status);

    /**
     * 业务方法：getContractDetail。
     */
    ContractDetailResponse getContractDetail(Long id);

    /**
     * 业务方法：updateStatus。
     */
    ContractDetailResponse updateStatus(Long id, ContractStatusUpdateRequest request);

    /**
     * 业务方法：recognizeAttachment。
     */
    ContractAttachmentResponse recognizeAttachment(Long contractId, ContractAttachmentOcrRequest request);

    /**
     * 业务方法：archiveContract。
     */
    ContractDetailResponse archiveContract(Long contractId, ContractArchiveRequest request);

    /**
     * 业务方法：sealContract。
     */
    ContractDetailResponse sealContract(Long contractId, ContractSealRequest request);
}
