package com.bkanent.common.model;

public record ContractSummaryDTO(
        Long id,
        String contractType,
        String status,
        String expiryDate
) {
}
