package com.bkanent.contract.service.impl;

import com.bkanent.contract.client.ContractEsignClientDispatcher;
import com.bkanent.contract.client.ContractEsignResult;
import com.bkanent.contract.config.ContractIntegrationProperties;
import com.bkanent.contract.entity.ContractAttachmentEntity;
import com.bkanent.contract.entity.ContractEntity;
import com.bkanent.contract.entity.ContractTemplateEntity;
import com.bkanent.contract.model.ContractArchiveRequest;
import com.bkanent.contract.model.ContractAttachmentOcrRequest;
import com.bkanent.contract.model.ContractAttachmentResponse;
import com.bkanent.contract.model.ContractDetailResponse;
import com.bkanent.contract.model.ContractSealRequest;
import com.bkanent.contract.model.ContractStatusUpdateRequest;
import com.bkanent.contract.model.ContractTemplateResponse;
import com.bkanent.contract.model.ContractTemplateUpsertRequest;
import com.bkanent.contract.model.ContractUpsertRequest;
import com.bkanent.contract.ocr.ContractOcrExtractResult;
import com.bkanent.contract.ocr.ContractOcrExtractorDispatcher;
import com.bkanent.contract.service.ContractAttachmentService;
import com.bkanent.contract.service.ContractDomainService;
import com.bkanent.contract.service.ContractManagementService;
import com.bkanent.contract.service.ContractTemplateService;
import com.bkanent.contract.validator.ContractStatusFlowValidator;
import com.bkanent.common.a2a.VerifiedDomainAuthorization;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Contract management service implementation.
 */
@Service
public class ContractManagementServiceImpl implements ContractManagementService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ContractDomainService contractDomainService;
    private final ContractTemplateService contractTemplateService;
    private final ContractAttachmentService contractAttachmentService;
    private final ContractEsignClientDispatcher contractEsignClientDispatcher;
    private final ContractOcrExtractorDispatcher contractOcrExtractorDispatcher;
    private final ContractStatusFlowValidator contractStatusFlowValidator;
    private final ContractIntegrationProperties contractIntegrationProperties;

    public ContractManagementServiceImpl(ContractDomainService contractDomainService,
                                         ContractTemplateService contractTemplateService,
                                         ContractAttachmentService contractAttachmentService,
                                         ContractEsignClientDispatcher contractEsignClientDispatcher,
                                         ContractOcrExtractorDispatcher contractOcrExtractorDispatcher,
                                         ContractStatusFlowValidator contractStatusFlowValidator,
                                         ContractIntegrationProperties contractIntegrationProperties) {
        this.contractDomainService = contractDomainService;
        this.contractTemplateService = contractTemplateService;
        this.contractAttachmentService = contractAttachmentService;
        this.contractEsignClientDispatcher = contractEsignClientDispatcher;
        this.contractOcrExtractorDispatcher = contractOcrExtractorDispatcher;
        this.contractStatusFlowValidator = contractStatusFlowValidator;
        this.contractIntegrationProperties = contractIntegrationProperties;
    }

    @Override
    public ContractTemplateResponse saveTemplate(ContractTemplateUpsertRequest request) {
        VerifiedDomainAuthorization.requireOperation("contract.template.write.all");
        ContractTemplateEntity entity = new ContractTemplateEntity();
        entity.setTemplateCode(request.templateCode());
        entity.setTemplateName(request.templateName());
        entity.setContractType(request.contractType());
        entity.setVersionNo(request.versionNo());
        entity.setTemplateContent(request.templateContent());
        entity.setTemplateFileUrl(request.templateFileUrl());
        entity.setStatus(defaultIfBlank(request.status(), "ACTIVE"));
        entity.setRemark(request.remark());
        contractTemplateService.save(entity);
        return toTemplateResponse(entity);
    }

    @Override
    public List<ContractTemplateResponse> listTemplates(String contractType) {
        VerifiedDomainAuthorization.requireOperation("contract.template.read.all");
        return contractTemplateService.listByContractType(contractType).stream()
                .map(this::toTemplateResponse)
                .toList();
    }

    @Override
    public ContractDetailResponse saveContract(ContractUpsertRequest request) {
        VerifiedDomainAuthorization.requireOperation("contract.create.own");
        long brokerId = VerifiedDomainAuthorization.requireBrokerIdentity();
        if (request.brokerId() != null && request.brokerId() != brokerId) {
            throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        }
        ContractEntity entity = new ContractEntity();
        entity.setTemplateId(request.templateId());
        entity.setContractNo(request.contractNo());
        entity.setTitle(request.title());
        entity.setContractType(request.contractType());
        entity.setStatus(defaultIfBlank(request.status(), "PENDING_SIGN"));
        entity.setExpiryDate(request.expiryDate());
        entity.setBrokerId(brokerId);
        entity.setListingId(request.listingId());
        entity.setCustomerName(request.customerName());
        entity.setPartyAName(request.partyAName());
        entity.setPartyBName(request.partyBName());
        entity.setDealAmount(request.dealAmount());
        entity.setSignedDocumentUrl(request.signedDocumentUrl());
        entity.setArchiveStatus("UNARCHIVED");
        entity.setSealStatus("PENDING");
        entity.setSignStartTime(nowText());
        entity.setRemark(request.remark());
        contractDomainService.save(entity);
        return toDetailResponse(entity, List.of());
    }

    @Override
    public List<ContractDetailResponse> listContracts(String contractType, String status) {
        Long brokerId = VerifiedDomainAuthorization.requireBrokerScope(
                "contract.read.own", "contract.read.all");
        List<ContractEntity> contracts = brokerId == null
                ? contractDomainService.listContracts(contractType, status)
                : contractDomainService.listContractsForBroker(contractType, status, brokerId);
        return contracts.stream()
                .map(entity -> toDetailResponse(entity, contractAttachmentService.listByContractId(entity.getId())))
                .toList();
    }

    @Override
    public ContractDetailResponse getContractDetail(Long id) {
        Long brokerId = VerifiedDomainAuthorization.requireBrokerScope(
                "contract.read.own", "contract.read.all");
        ContractEntity entity = brokerId == null
                ? contractDomainService.getById(id)
                : contractDomainService.getContractForBroker(id, brokerId);
        if (entity == null) {
            return null;
        }
        return toDetailResponse(entity, contractAttachmentService.listByContractId(id));
    }

    @Override
    public ContractDetailResponse updateStatus(Long id, ContractStatusUpdateRequest request) {
        ContractEntity entity = requireContract(id, "contract.status.update.own", "contract.status.update.all");
        contractStatusFlowValidator.validateStatusTransition(entity.getStatus(), request.status());
        entity.setStatus(request.status());
        entity.setRemark(request.remark());
        if ("SIGNED".equalsIgnoreCase(request.status()) || "BOTH_SIGNED".equalsIgnoreCase(request.status())) {
            entity.setBothSignedTime(nowText());
        }
        if ("DISPUTE".equalsIgnoreCase(request.status())) {
            entity.setDisputeTime(nowText());
        }
        contractDomainService.updateById(entity);
        return detail(entity);
    }

    @Override
    public ContractAttachmentResponse recognizeAttachment(Long contractId, ContractAttachmentOcrRequest request) {
        ContractEntity contract = requireContract(contractId, "contract.attachment.ocr.own", "contract.attachment.ocr.all");
        ContractOcrExtractResult extractResult = contractOcrExtractorDispatcher.extract(
                contractIntegrationProperties.getOcrProvider(),
                request.attachmentType(),
                request.fileName(),
                request.fileUrl()
        );

        ContractAttachmentEntity attachment = new ContractAttachmentEntity();
        attachment.setContractId(contractId);
        attachment.setAttachmentType(request.attachmentType());
        attachment.setFileName(request.fileName());
        attachment.setFileUrl(request.fileUrl());
        attachment.setOcrStatus("DONE");
        attachment.setOcrProvider(extractResult.provider());
        attachment.setOcrTime(nowText());
        attachment.setOcrText(extractResult.plainText());
        attachment.setOcrStructuredData(extractResult.structuredData());
        attachment.setRemark(request.remark());
        contractAttachmentService.save(attachment);

        contract.setOcrSummary(buildOcrSummary(contractId));
        contractDomainService.updateById(contract);
        return toAttachmentResponse(attachment);
    }

    @Override
    public ContractDetailResponse archiveContract(Long contractId, ContractArchiveRequest request) {
        ContractEntity entity = requireContract(contractId, "contract.archive.own", "contract.archive.all");
        contractStatusFlowValidator.validateArchiveAction(entity.getStatus());
        entity.setArchiveStatus("ARCHIVED");
        entity.setArchivedTime(nowText());
        entity.setStatus("ARCHIVED");
        entity.setRemark(request.archiveRemark());
        contractDomainService.updateById(entity);
        return detail(entity);
    }

    @Override
    public ContractDetailResponse sealContract(Long contractId, ContractSealRequest request) {
        ContractEntity entity = requireContract(contractId, "contract.seal.own", "contract.seal.all");
        contractStatusFlowValidator.validateSealAction(entity.getStatus(), entity.getSealStatus());
        String provider = defaultIfBlank(request.sealProvider(), contractIntegrationProperties.getEsignProvider());
        if (isMockEsign(provider)) {
            throw new UnsupportedOperationException("模拟电子签章不能将合同标记为已用印");
        }
        ContractEsignResult esignResult = contractEsignClientDispatcher.sealContract(
                provider,
                entity,
                request.signedDocumentUrl()
        );
        if (esignResult == null || isMockEsign(esignResult.provider())) {
            throw new UnsupportedOperationException("模拟电子签章结果不能将合同标记为已用印");
        }
        entity.setSealStatus("SEALED");
        entity.setSealProvider(esignResult.provider());
        entity.setSealTime(nowText());
        entity.setSignedDocumentUrl(esignResult.signedDocumentUrl());
        entity.setExternalSealNo(esignResult.externalSealNo());
        contractDomainService.updateById(entity);
        return detail(entity);
    }

    private ContractEntity requireContract(Long id, String ownOperation, String businessWideOperation) {
        Long brokerId = VerifiedDomainAuthorization.requireBrokerScope(ownOperation, businessWideOperation);
        ContractEntity entity = brokerId == null
                ? contractDomainService.getById(id)
                : contractDomainService.getContractForBroker(id, brokerId);
        if (entity == null) {
            throw new IllegalArgumentException("Contract not found: " + id);
        }
        return entity;
    }

    private ContractDetailResponse detail(ContractEntity entity) {
        return toDetailResponse(entity, contractAttachmentService.listByContractId(entity.getId()));
    }

    private boolean isMockEsign(String provider) {
        return provider != null && (provider.equalsIgnoreCase(com.bkanent.contract.config.ContractProviderNames.ESIGN_MOCK)
                || provider.equalsIgnoreCase(com.bkanent.contract.config.ContractProviderNames.ALIAS_MOCK));
    }

    private ContractTemplateResponse toTemplateResponse(ContractTemplateEntity entity) {
        return new ContractTemplateResponse(
                entity.getId(),
                entity.getTemplateCode(),
                entity.getTemplateName(),
                entity.getContractType(),
                entity.getVersionNo(),
                entity.getTemplateFileUrl(),
                entity.getStatus(),
                entity.getRemark()
        );
    }

    private ContractDetailResponse toDetailResponse(ContractEntity entity, List<ContractAttachmentEntity> attachments) {
        return new ContractDetailResponse(
                entity.getId(),
                entity.getTemplateId(),
                entity.getContractNo(),
                entity.getTitle(),
                entity.getContractType(),
                entity.getStatus(),
                entity.getExpiryDate(),
                entity.getBrokerId(),
                entity.getListingId(),
                entity.getCustomerName(),
                entity.getPartyAName(),
                entity.getPartyBName(),
                entity.getDealAmount(),
                entity.getSignedDocumentUrl(),
                entity.getExternalSealNo(),
                entity.getSignStartTime(),
                entity.getBothSignedTime(),
                entity.getArchivedTime(),
                entity.getDisputeTime(),
                entity.getArchiveStatus(),
                entity.getSealStatus(),
                entity.getSealProvider(),
                entity.getSealTime(),
                entity.getOcrSummary(),
                entity.getRemark(),
                attachments.stream().map(this::toAttachmentResponse).toList()
        );
    }

    private ContractAttachmentResponse toAttachmentResponse(ContractAttachmentEntity entity) {
        return new ContractAttachmentResponse(
                entity.getId(),
                entity.getContractId(),
                entity.getAttachmentType(),
                entity.getFileName(),
                entity.getFileUrl(),
                entity.getOcrStatus(),
                entity.getOcrText(),
                entity.getOcrStructuredData(),
                entity.getOcrProvider(),
                entity.getOcrTime(),
                entity.getRemark()
        );
    }

    private String buildOcrSummary(Long contractId) {
        return contractAttachmentService.listByContractId(contractId).stream()
                .map(attachment -> attachment.getAttachmentType() + " recognized")
                .distinct()
                .reduce((left, right) -> left + "; " + right)
                .orElse("No OCR result available");
    }

    private String nowText() {
        return LocalDateTime.now().format(DATE_TIME_FORMATTER);
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }
}
