package com.bkanent.contract.model;

/**
 * Contract attachment response.
 */
public record ContractAttachmentResponse(
        Long id,
        Long contractId,
        String attachmentType,
        String fileName,
        String fileUrl,
        String ocrStatus,
        String ocrText,
        String ocrStructuredData,
        String ocrProvider,
        String ocrTime,
        String remark
) {
}
