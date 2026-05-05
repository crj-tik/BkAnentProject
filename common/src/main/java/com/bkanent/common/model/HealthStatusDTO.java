package com.bkanent.common.model;

public record HealthStatusDTO(
        String service,
        String status,
        String version
) {
}
