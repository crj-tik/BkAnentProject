package com.bkanent.common.model;

/**
 * Health response DTO.
 */
public record HealthStatusDTO(
        String service,
        String status,
        String version
) {
}
