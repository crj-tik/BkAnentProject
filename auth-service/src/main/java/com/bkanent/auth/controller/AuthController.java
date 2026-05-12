package com.bkanent.auth.controller;

import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.service.UserAccountService;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.AuthLoginRequest;
import com.bkanent.common.model.AuthTokenDTO;
import com.bkanent.common.model.HealthStatusDTO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    public AuthController(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    @PostMapping("/login")
    public ApiResponse<AuthTokenDTO> login(@RequestBody AuthLoginRequest request) {
        UserAccountEntity account = userAccountService.findByUsername(request.username());
        if (account == null) {
            return ApiResponse.fail("AUTH_404", "user not found");
        }
        return ApiResponse.ok(new AuthTokenDTO(
                "mock-access-token-" + account.getUsername(),
                "mock-refresh-token-" + account.getUsername(),
                account.getId(),
                account.getRoleCode()
        ));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        return ApiResponse.ok(null);
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("auth-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
