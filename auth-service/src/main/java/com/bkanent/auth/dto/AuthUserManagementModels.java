package com.bkanent.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class AuthUserManagementModels {

    private AuthUserManagementModels() {
    }

    public record CurrentUser(Long userId, String username, String displayName,
                              String roleCode, String roleName, String tenantCode) {
    }

    public record RoleOption(String roleCode, String roleName, String description) {
    }

    public record UserItem(Long userId, String username, String displayName,
                           String roleCode, String roleName, String tenantCode,
                           String accountStatus, LocalDateTime createdAt) {
    }

    public record PageResult<T>(List<T> records, long total, long page, long pageSize) {
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{3,64}") String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 64) String displayName,
            @NotBlank @Size(max = 32) String roleCode,
            @NotBlank @Size(max = 64) String tenantCode) {
    }

    public record UpdateUserRequest(
            @NotBlank @Size(max = 64) String displayName,
            @NotBlank @Size(max = 32) String roleCode,
            @NotBlank @Size(max = 64) String tenantCode,
            @NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String accountStatus) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 8, max = 72) String password) {
    }
}
