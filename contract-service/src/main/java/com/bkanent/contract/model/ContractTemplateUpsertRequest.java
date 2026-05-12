package com.bkanent.contract.model;

/**
 * Contract template create or update request.
 */
public record ContractTemplateUpsertRequest(
        String templateCode,
        String templateName,
        String contractType,
        Integer versionNo,
        String templateContent,
        String templateFileUrl,
        String status,
        String remark
) {
}
