package com.bkanent.contract.model;

/**
 * OCR request for a contract attachment.
 */
public record ContractAttachmentOcrRequest(
        String attachmentType,
        String fileName,
        String fileUrl,
        String remark
) {
}
