package com.bkanent.contract.model;

/**
 * Contract sealing request.
 */
public record ContractSealRequest(
        String sealProvider,
        String signedDocumentUrl
) {
}
