package com.bkanent.auth;

import com.bkanent.auth.config.AuthTokenProperties;
import com.bkanent.auth.controller.AuthController;
import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.service.AuthTokenService;
import com.bkanent.auth.service.UserAccountService;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.AuthLoginRequest;
import com.bkanent.common.model.AuthTokenDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private UserAccountService userAccountService;
    private AuthTokenService authTokenService;
    private AuthController authController;
    private UserAccountEntity activeAccount;

    @BeforeEach
    void setUp() {
        userAccountService = mock(UserAccountService.class);
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setSecret("unit-test-auth-secret");
        authTokenService = new AuthTokenService(properties);
        authTokenService.initialize();
        authController = new AuthController(
                userAccountService,
                new BCryptPasswordEncoder(),
                authTokenService
        );

        activeAccount = new UserAccountEntity();
        activeAccount.setId(7L);
        activeAccount.setUsername("broker01");
        activeAccount.setPasswordHash(new BCryptPasswordEncoder().encode("correct-password"));
        activeAccount.setRoleCode("BROKER");
        activeAccount.setAccountStatus("ACTIVE");
        activeAccount.setDeleted(0);
        when(userAccountService.findByUsername("broker01")).thenReturn(activeAccount);
    }

    @Test
    void rejectsWrongPasswordAndUnknownUser() {
        ApiResponse<AuthTokenDTO> wrongPassword = authController.login(
                new AuthLoginRequest("broker01", "wrong-password", null)
        );
        assertFalse(wrongPassword.success());

        ApiResponse<AuthTokenDTO> unknownUser = authController.login(
                new AuthLoginRequest("missing", "correct-password", null)
        );
        assertFalse(unknownUser.success());
    }

    @Test
    void logsInActiveUserAndLogoutRevokesAccessToken() {
        ApiResponse<AuthTokenDTO> response = authController.login(
                new AuthLoginRequest("broker01", "correct-password", null)
        );

        assertTrue(response.success());
        assertNotNull(response.data());
        assertTrue(authTokenService.isValidAccessToken(response.data().accessToken()));

        authController.logout("Bearer " + response.data().accessToken());

        assertFalse(authTokenService.isValidAccessToken(response.data().accessToken()));
    }

    @Test
    void rejectsInactiveUser() {
        activeAccount.setAccountStatus("DISABLED");

        ApiResponse<AuthTokenDTO> response = authController.login(
                new AuthLoginRequest("broker01", "correct-password", null)
        );

        assertFalse(response.success());
    }
}
