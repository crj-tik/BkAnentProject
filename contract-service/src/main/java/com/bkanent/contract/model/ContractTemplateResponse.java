package com.bkanent.contract.model;

/**
 * Contract template response.
 */
public record ContractTemplateResponse(
        Long id,
        String templateCode,
        String templateName,
        String contractType,
        Integer versionNo,
        String templateFileUrl,
        String status,
        String remark
) {
}
