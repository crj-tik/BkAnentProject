package com.bkanent.auth.controller;

import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.dto.AuthUserManagementModels.CreateUserRequest;
import com.bkanent.auth.dto.AuthUserManagementModels.CurrentUser;
import com.bkanent.auth.dto.AuthUserManagementModels.PageResult;
import com.bkanent.auth.dto.AuthUserManagementModels.RoleOption;
import com.bkanent.auth.dto.AuthUserManagementModels.ResetPasswordRequest;
import com.bkanent.auth.dto.AuthUserManagementModels.UpdateUserRequest;
import com.bkanent.auth.dto.AuthUserManagementModels.UserItem;
import com.bkanent.auth.service.AuthTokenService;
import com.bkanent.auth.service.UserManagementService;
import com.bkanent.auth.service.UserAccountService;
import com.bkanent.auth.service.UserManagementException;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.AuthLoginRequest;
import com.bkanent.common.model.AuthRefreshRequest;
import com.bkanent.common.model.AuthTokenDTO;
import com.bkanent.common.model.HealthStatusDTO;
import jakarta.validation.Valid;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * Authentication controller.
 */
@RestController
@RequestMapping("/auth")
@Validated
public class AuthController {

    private final UserAccountService userAccountService;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService authTokenService;
    private final UserManagementService userManagementService;

    public AuthController(UserAccountService userAccountService,
                          PasswordEncoder passwordEncoder,
                          AuthTokenService authTokenService,
                          UserManagementService userManagementService) {
        this.userAccountService = userAccountService;
        this.passwordEncoder = passwordEncoder;
        this.authTokenService = authTokenService;
        this.userManagementService = userManagementService;
    }

    @PostMapping("/login")
    public ApiResponse<AuthTokenDTO> login(@RequestBody AuthLoginRequest request) {
        if (request == null || request.username() == null || request.username().isBlank()
                || request.password() == null || request.password().isBlank()) {
            return ApiResponse.fail("AUTH_400", "username and password are required");
        }
        UserAccountEntity account = userAccountService.findByUsername(request.username());
        if (account == null || !Integer.valueOf(0).equals(account.getDeleted())
                || !"ACTIVE".equalsIgnoreCase(account.getAccountStatus())
                || !hasAccountAuthorization(account)
                || !passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            return ApiResponse.fail("AUTH_401", "invalid username or password");
        }
        return ApiResponse.ok(new AuthTokenDTO(
                authTokenService.issueAccessToken(account),
                authTokenService.issueRefreshToken(account),
                account.getId(),
                account.getRoleCode(),
                account.getDisplayName(),
                userManagementService.getRoleNameForCode(account.getRoleCode()),
                account.getTenantCode()
        ));
    }

    /**
     * 刷新访问令牌：refresh token 一次性轮换——旧的用后即废，
     * 前端必须以本次响应的新 refreshToken 替换本地存储（见 LR-16）。
     */
    @PostMapping("/refresh")
    public ApiResponse<AuthTokenDTO> refresh(@RequestBody AuthRefreshRequest request) {
        if (request == null || request.refreshToken() == null || request.refreshToken().isBlank()) {
            return ApiResponse.fail("AUTH_400", "refreshToken is required");
        }
        AuthTokenService.TokenPrincipal principal = authTokenService.parse(request.refreshToken());
        if (principal == null || !"refresh".equals(principal.tokenType())) {
            return ApiResponse.fail("AUTH_401", "invalid or expired refresh token");
        }
        UserAccountEntity account = userAccountService.getById(principal.userId());
        if (account == null || !Integer.valueOf(0).equals(account.getDeleted())
                || !"ACTIVE".equalsIgnoreCase(account.getAccountStatus())
                || !hasAccountAuthorization(account)) {
            return ApiResponse.fail("AUTH_401", "账号已停用或权限已失效");
        }
        // 一次性轮换：旧 refresh token 用后即废。token 无 nonce、秒级时间戳，
        // 同一秒内重签会得到相同串——此时跳过 revoke，避免新 token「出生即被吊销」。
        String newRefreshToken = authTokenService.issueRefreshToken(account);
        if (!request.refreshToken().equals(newRefreshToken)) {
            authTokenService.revoke(request.refreshToken());
        }
        return ApiResponse.ok(new AuthTokenDTO(
                authTokenService.issueAccessToken(account),
                newRefreshToken,
                account.getId(),
                account.getRoleCode(),
                account.getDisplayName(),
                userManagementService.getRoleNameForCode(account.getRoleCode()),
                account.getTenantCode()
        ));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (authorization != null && !authorization.isBlank()) {
            String token = authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                    ? authorization.substring(7).trim()
                    : authorization.trim();
            if (userManagementService.isVersionedAuthorization()) userManagementService.logout(token);
            else authTokenService.revoke(token);
        }
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUser> currentUser(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        UserAccountEntity account = requireAuthenticatedAccount(authorization);
        return ApiResponse.ok(userManagementService.currentUser(account));
    }

    @GetMapping("/roles")
    public ApiResponse<List<RoleOption>> roles(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        requireAdmin(authorization);
        return ApiResponse.ok(userManagementService.listRoles());
    }

    @GetMapping("/users")
    public ApiResponse<PageResult<UserItem>> users(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(name = "page", defaultValue = "1") long page,
            @RequestParam(name = "pageSize", defaultValue = "20") long pageSize,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "roleCode", required = false) String roleCode,
            @RequestParam(name = "accountStatus", required = false) String accountStatus) {
        requireAdmin(authorization);
        return ApiResponse.ok(userManagementService.listUsers(page, pageSize, keyword, roleCode, accountStatus));
    }

    @PostMapping("/users")
    public ResponseEntity<ApiResponse<UserItem>> createUser(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody CreateUserRequest request) {
        UserAccountEntity actor = requireAdmin(authorization);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(userManagementService.isVersionedAuthorization()
                        ? userManagementService.createUser(accessToken(authorization), actor.getId(), request) : userManagementService.createUser(request)));
    }

    @PutMapping("/users/{userId}")
    public ApiResponse<UserItem> updateUser(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable("userId") long userId,
            @Valid @RequestBody UpdateUserRequest request) {
        UserAccountEntity actor = requireAdmin(authorization);
        return ApiResponse.ok(userManagementService.isVersionedAuthorization()
                ? userManagementService.updateUser(accessToken(authorization), actor.getId(), userId, request)
                : userManagementService.updateUser(actor.getId(), userId, request));
    }

    @PutMapping("/users/{userId}/password")
    public ApiResponse<Void> resetPassword(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable("userId") long userId,
            @Valid @RequestBody ResetPasswordRequest request) {
        UserAccountEntity actor = requireAdmin(authorization);
        if (userManagementService.isVersionedAuthorization()) userManagementService.resetPassword(accessToken(authorization), actor.getId(), userId, request.password());
        else userManagementService.resetPassword(userId, request.password());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/users/{userId}")
    public ApiResponse<Void> deleteUser(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable("userId") long userId) {
        UserAccountEntity actor = requireAdmin(authorization);
        if (userManagementService.isVersionedAuthorization()) userManagementService.deleteUser(accessToken(authorization), actor.getId(), userId);
        else userManagementService.deleteUser(actor.getId(), userId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("auth-service", "UP", "1.0.0-SNAPSHOT"));
    }

    private UserAccountEntity requireAdmin(String authorization) {
        UserAccountEntity account = requireAuthenticatedAccount(authorization);
        boolean permitted;
        if (userManagementService.isVersionedAuthorization()) {
            var snapshot = userManagementService.authorization(account.getId());
            permitted = snapshot.businessAdmin() && snapshot.hasPermission("auth.users.manage", java.time.Instant.now());
        } else permitted = "ADMIN".equals(account.getRoleCode());
        if (!permitted) {
            throw new UserManagementException(HttpStatus.FORBIDDEN, "AUTH_FORBIDDEN", "需要管理员权限");
        }
        return account;
    }

    private boolean hasAccountAuthorization(UserAccountEntity account) {
        return userManagementService.isVersionedAuthorization() ? userManagementService.isAuthorizedAccount(account)
                : UserManagementService.isSupportedRole(account.getRoleCode())
                  && userManagementService.isActiveRole(account.getRoleCode());
    }

    private static String accessToken(String authorization) {
        return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).trim()
                : authorization == null ? null : authorization.trim();
    }

    private UserAccountEntity requireAuthenticatedAccount(String authorization) {
        String token = accessToken(authorization);
        AuthTokenService.TokenPrincipal principal = authTokenService.parse(token);
        if (principal == null || !"access".equals(principal.tokenType())) {
            throw new UserManagementException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHORIZED", "登录状态已失效");
        }
        UserAccountEntity account = userAccountService.getById(principal.userId());
        if (account == null || !Integer.valueOf(0).equals(account.getDeleted())
                || !"ACTIVE".equalsIgnoreCase(account.getAccountStatus())
                || !hasAccountAuthorization(account)) {
            throw new UserManagementException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHORIZED", "账号已停用或权限已失效");
        }
        return account;
    }
}
