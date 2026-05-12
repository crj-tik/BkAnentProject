package com.bkanent.contract.model;

/**
 * Contract status update request.
 */
public record ContractStatusUpdateRequest(
        String status,
        String remark
) {
}
