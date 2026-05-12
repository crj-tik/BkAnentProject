package com.bkanent.contract.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Contract detail response.
 */
public record ContractDetailResponse(
        Long id,
        Long templateId,
        String contractNo,
        String title,
        String contractType,
        String status,
        String expiryDate,
        Long brokerId,
        Long listingId,
        String customerName,
        String partyAName,
        String partyBName,
        BigDecimal dealAmount,
        String signedDocumentUrl,
        String externalSealNo,
        String signStartTime,
        String bothSignedTime,
        String archivedTime,
        String disputeTime,
        String archiveStatus,
        String sealStatus,
        String sealProvider,
        String sealTime,
        String ocrSummary,
        String remark,
        List<ContractAttachmentResponse> attachments
) {
}
