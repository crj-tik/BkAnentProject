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
                              String roleCode, String roleName, String tenantCode,
                              boolean businessAdmin, List<String> roles, long permissionRevision, long credentialEpoch,
                              List<OrganizationSummary> organizations) {
        public CurrentUser { roles = List.copyOf(roles); organizations = List.copyOf(organizations); }
        public CurrentUser(Long userId, String username, String displayName, String roleCode, String roleName,
                           String tenantCode, boolean businessAdmin, List<String> roles,
                           long permissionRevision, long credentialEpoch) {
            this(userId, username, displayName, roleCode, roleName, tenantCode, businessAdmin, roles,
                    permissionRevision, credentialEpoch, List.of());
        }
        public CurrentUser(Long userId, String username, String displayName, String roleCode, String roleName, String tenantCode) {
            this(userId, username, displayName, roleCode, roleName, tenantCode, false, List.of(), 0, 0, List.of());
        }
    }

    public record OrganizationSummary(Long id, String code, String name, String type, Long parentId, boolean primary) { }

    public record RoleOption(String roleCode, String roleName, String description) {
    }

    public record UserItem(Long userId, String username, String displayName,
                           String roleCode, String roleName, String tenantCode,
                           String accountStatus, LocalDateTime createdAt,
                           boolean businessAdmin, List<String> roles, long permissionRevision, long credentialEpoch) {
        public UserItem { roles = List.copyOf(roles); }
        public UserItem(Long userId, String username, String displayName, String roleCode,
                        String roleName, String tenantCode, String accountStatus, LocalDateTime createdAt) {
            this(userId, username, displayName, roleCode, roleName, tenantCode, accountStatus, createdAt,
                    false, List.of(), 0, 0);
        }
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
