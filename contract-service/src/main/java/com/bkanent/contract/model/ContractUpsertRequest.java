package com.bkanent.contract.model;

import java.math.BigDecimal;

/**
 * Contract create or update request.
 */
public record ContractUpsertRequest(
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
        String remark
) {
}
