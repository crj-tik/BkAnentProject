package com.bkanent.auth.controller;

import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.service.AuthTokenService;
import com.bkanent.auth.service.UserAccountService;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.AuthLoginRequest;
import com.bkanent.common.model.AuthTokenDTO;
import com.bkanent.common.model.HealthStatusDTO;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication controller.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserAccountService userAccountService;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService authTokenService;

    public AuthController(UserAccountService userAccountService,
                          PasswordEncoder passwordEncoder,
                          AuthTokenService authTokenService) {
        this.userAccountService = userAccountService;
        this.passwordEncoder = passwordEncoder;
        this.authTokenService = authTokenService;
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
                || !passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            return ApiResponse.fail("AUTH_401", "invalid username or password");
        }
        return ApiResponse.ok(new AuthTokenDTO(
                authTokenService.issueAccessToken(account),
                authTokenService.issueRefreshToken(account),
                account.getId(),
                account.getRoleCode()
        ));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (authorization != null && !authorization.isBlank()) {
            String token = authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                    ? authorization.substring(7).trim()
                    : authorization.trim();
            authTokenService.revoke(token);
        }
        return ApiResponse.ok(null);
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("auth-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
