package com.bkanent.common.model;

import java.util.List;
import java.util.TreeSet;

public record AuthTokenDTO(
        String accessToken,
        String refreshToken,
        Long userId,
        String roleCode,
        String displayName,
        String roleName,
        String tenantCode,
        boolean businessAdmin,
        List<String> roles,
        long permissionRevision,
        long credentialEpoch
) {
    public AuthTokenDTO {
        roles = List.copyOf(new TreeSet<>(roles == null ? List.of() : roles));
    }

    public AuthTokenDTO(String accessToken, String refreshToken, Long userId, String roleCode,
                        String displayName, String roleName, String tenantCode) {
        this(accessToken, refreshToken, userId, roleCode, displayName, roleName, tenantCode,
                false, List.of(), 0, 0);
    }

    @Override public String toString() { return "AuthTokenDTO[REDACTED]"; }
}
