package com.bkanent.common.model;

public record AuthTokenDTO(
        String accessToken,
        String refreshToken,
        Long userId,
        String roleCode
) {
}
