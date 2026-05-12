package com.bkanent.contract.client;

/**
 * 电子签章结果。
 */
public record ContractEsignResult(
        String provider,
        String signedDocumentUrl,
        String externalSealNo,
        String message
) {
}
