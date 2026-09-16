package com.bkanent.auth;

import com.bkanent.auth.config.AuthTokenProperties;
import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.rpc.AuthPermissionRpcServiceImpl;
import com.bkanent.auth.service.AuthTokenService;
import com.bkanent.auth.service.UserAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthPermissionRpcServiceImplTest {

    private UserAccountService userAccountService;
    private AuthTokenService authTokenService;
    private AuthPermissionRpcServiceImpl permissionService;
    private UserAccountEntity activeAccount;

    @BeforeEach
    void setUp() {
        userAccountService = mock(UserAccountService.class);
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setSecret("unit-test-auth-secret");
        authTokenService = new AuthTokenService(properties);
        authTokenService.initialize();
        permissionService = new AuthPermissionRpcServiceImpl(authTokenService, userAccountService);

        activeAccount = new UserAccountEntity();
        activeAccount.setId(7L);
        activeAccount.setAccountStatus("ACTIVE");
        activeAccount.setDeleted(0);
        when(userAccountService.getById(7L)).thenReturn(activeAccount);
    }

    @Test
    void validatesOnlySignedAccessTokens() {
        String accessToken = authTokenService.issueAccessToken(activeAccount);
        String refreshToken = authTokenService.issueRefreshToken(activeAccount);

        assertTrue(permissionService.validateToken(accessToken));
        assertFalse(permissionService.validateToken(refreshToken));
        assertFalse(permissionService.validateToken("mock-access-token-7"));
    }

    @Test
    void resolvesOnlyActiveAccountPrincipals() {
        String accessToken = authTokenService.issueAccessToken(activeAccount);

        assertEquals(7L, permissionService.resolvePrincipal(accessToken).userId());

        activeAccount.setAccountStatus("DISABLED");
        assertNull(permissionService.resolvePrincipal(accessToken));
    }

    @Test
    void onlyActiveNonDeletedAccountsHavePermission() {
        assertTrue(permissionService.hasPermission(7L, "listing.read"));
        assertFalse(permissionService.hasPermission(7L, ""));

        activeAccount.setAccountStatus("DISABLED");
        assertFalse(permissionService.hasPermission(7L, "listing.read"));
        assertFalse(permissionService.hasPermission(404L, "listing.read"));
    }
}
