package com.bkanent.common.model;

public record AuthLoginRequest(
        String username,
        String password,
        String tenantCode
) {
}
