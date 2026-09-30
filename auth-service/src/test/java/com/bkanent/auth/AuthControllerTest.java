package com.bkanent.auth;

import com.bkanent.auth.config.AuthTokenProperties;
import com.bkanent.auth.controller.AuthController;
import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.service.AuthTokenService;
import com.bkanent.auth.service.InMemoryTokenRevocationStore;
import com.bkanent.auth.service.UserAccountService;
import com.bkanent.auth.service.UserManagementService;
import com.bkanent.auth.rpc.AuthPermissionRpcServiceImpl;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.AuthLoginRequest;
import com.bkanent.common.model.AuthRefreshRequest;
import com.bkanent.common.model.AuthTokenDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private UserAccountService userAccountService;
    private AuthTokenService authTokenService;
    private UserManagementService userManagementService;
    private AuthController authController;
    private UserAccountEntity activeAccount;

    @BeforeEach
    void setUp() {
        userAccountService = mock(UserAccountService.class);
        userManagementService = mock(UserManagementService.class);
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setSecret("unit-test-auth-secret");
        authTokenService = new AuthTokenService(properties, new InMemoryTokenRevocationStore());
        authTokenService.initialize();
        authController = new AuthController(
                userAccountService,
                new BCryptPasswordEncoder(),
                authTokenService,
                userManagementService
        );

        activeAccount = new UserAccountEntity();
        activeAccount.setId(7L);
        activeAccount.setUsername("broker01");
        activeAccount.setPasswordHash(new BCryptPasswordEncoder().encode("correct-password"));
        activeAccount.setRoleCode("BROKER");
        activeAccount.setAccountStatus("ACTIVE");
        activeAccount.setDeleted(0);
        when(userAccountService.findByUsername("broker01")).thenReturn(activeAccount);
        when(userManagementService.isActiveRole("BROKER")).thenReturn(true);
        when(userManagementService.getRoleNameForCode("BROKER")).thenReturn("经纪人");
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
        when(userAccountService.getById(7L)).thenReturn(activeAccount);
        when(userManagementService.isActiveRole("BROKER")).thenReturn(true);
        AuthPermissionRpcServiceImpl authRpc = new AuthPermissionRpcServiceImpl(
                authTokenService, userAccountService, userManagementService);
        assertNotNull(authRpc.resolvePrincipal(response.data().accessToken()));

        authController.logout("Bearer " + response.data().accessToken());

        assertFalse(authTokenService.isValidAccessToken(response.data().accessToken()));
        assertNull(authRpc.resolvePrincipal(response.data().accessToken()),
                "the Gateway auth RPC must reject the token immediately after logout");
    }

    @Test
    void refreshRotatesTokensAndRevokesOldRefreshToken() throws InterruptedException {
        ApiResponse<AuthTokenDTO> login = authController.login(
                new AuthLoginRequest("broker01", "correct-password", null)
        );
        assertTrue(login.success());
        when(userAccountService.getById(7L)).thenReturn(activeAccount);
        // token 无 nonce、秒级时间戳：隔一秒刷新，保证新旧 token 串必然不同
        Thread.sleep(1100);

        ApiResponse<AuthTokenDTO> refreshed = authController.refresh(
                new AuthRefreshRequest(login.data().refreshToken())
        );

        assertTrue(refreshed.success());
        assertNotNull(refreshed.data().accessToken());
        assertNotEquals(login.data().accessToken(), refreshed.data().accessToken());
        assertNotEquals(login.data().refreshToken(), refreshed.data().refreshToken());
        assertTrue(authTokenService.isValidAccessToken(refreshed.data().accessToken()));
        // 一次性轮换：旧 refresh token 刷新后立即失效（防 replay）
        ApiResponse<AuthTokenDTO> replay = authController.refresh(
                new AuthRefreshRequest(login.data().refreshToken())
        );
        assertFalse(replay.success());
    }

    @Test
    void refreshRejectsAccessTokenAndBlankToken() {
        ApiResponse<AuthTokenDTO> login = authController.login(
                new AuthLoginRequest("broker01", "correct-password", null)
        );

        // access token 不能当 refresh token 用（tokenType 校验）
        ApiResponse<AuthTokenDTO> wrongType = authController.refresh(
                new AuthRefreshRequest(login.data().accessToken())
        );
        assertFalse(wrongType.success());

        ApiResponse<AuthTokenDTO> blank = authController.refresh(
                new AuthRefreshRequest(" ")
        );
        assertFalse(blank.success());

        ApiResponse<AuthTokenDTO> garbage = authController.refresh(
                new AuthRefreshRequest("v1.not-a-token.sig")
        );
        assertFalse(garbage.success());
    }

    @Test
    void refreshRejectsDisabledAccount() {
        ApiResponse<AuthTokenDTO> login = authController.login(
                new AuthLoginRequest("broker01", "correct-password", null)
        );
        activeAccount.setAccountStatus("DISABLED");
        when(userAccountService.getById(7L)).thenReturn(activeAccount);

        ApiResponse<AuthTokenDTO> refreshed = authController.refresh(
                new AuthRefreshRequest(login.data().refreshToken())
        );

        assertFalse(refreshed.success());
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
