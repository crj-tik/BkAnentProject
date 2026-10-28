package com.bkanent.auth;

import com.bkanent.auth.config.AuthTokenProperties;
import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.rpc.AuthPermissionRpcServiceImpl;
import com.bkanent.auth.service.AuthTokenService;
import com.bkanent.auth.service.InMemoryTokenRevocationStore;
import com.bkanent.auth.service.UserAccountService;
import com.bkanent.auth.service.UserManagementService;
import com.bkanent.auth.authorization.EffectivePermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    private UserManagementService userManagementService;
    private UserAccountEntity activeAccount;

    @BeforeEach
    void setUp() {
        userAccountService = mock(UserAccountService.class);
        userManagementService = mock(UserManagementService.class);
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setSecret("unit-test-auth-secret");
        authTokenService = new AuthTokenService(properties, new InMemoryTokenRevocationStore());
        authTokenService.initialize();
        permissionService = new AuthPermissionRpcServiceImpl(authTokenService, userAccountService, userManagementService);

        activeAccount = new UserAccountEntity();
        activeAccount.setId(7L);
        activeAccount.setAccountStatus("ACTIVE");
        activeAccount.setDeleted(0);
        when(userAccountService.getById(7L)).thenReturn(activeAccount);
        activeAccount.setRoleCode("BROKER");
        when(userManagementService.isActiveRole("BROKER")).thenReturn(true);
        when(userManagementService.isActiveRole("ADMIN")).thenReturn(true);
    }

    @Test
    void rejectsLegacyRoleEvenWhenRoleRepositorySaysActive() {
        activeAccount.setRoleCode("MANAGER");
        when(userManagementService.isActiveRole("MANAGER")).thenReturn(true);
        assertFalse(permissionService.validateToken(authTokenService.issueAccessToken(activeAccount)));
        assertFalse(permissionService.hasPermission(7L, "listing.read"));
    }

    @Test
    void managementRequiresAdminAndUnknownPermissionsAreDenied() {
        assertFalse(permissionService.hasPermission(7L, "agent.rag.manage"));
        assertTrue(permissionService.hasPermission(7L, "agent.chat.use"));
        assertFalse(permissionService.hasPermission(7L, "invented.permission"));
        activeAccount.setRoleCode("ADMIN");
        assertTrue(permissionService.hasPermission(7L, "agent.rag.manage"));
        activeAccount.setDeleted(1);
        assertFalse(permissionService.hasPermission(7L, "agent.rag.manage"));
    }

    @Test
    void revocationAndCurrentRoleInvalidationRejectPreviouslyValidToken() {
        String token = authTokenService.issueAccessToken(activeAccount);
        assertTrue(permissionService.validateToken(token));
        when(userManagementService.isActiveRole("BROKER")).thenReturn(false);
        assertFalse(permissionService.validateToken(token));
        when(userManagementService.isActiveRole("BROKER")).thenReturn(true);
        authTokenService.revoke(token);
        assertNull(permissionService.resolvePrincipal(token));
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

    @Test
    void versionedBaselineActionsNeedAnActiveAuthSnapshotButNoPermissionGrant() {
        when(userManagementService.isVersionedAuthorization()).thenReturn(true);
        when(userManagementService.authorization(7L)).thenReturn(new EffectivePermissionService.Snapshot(
                7, 4, 2, "ACTIVE", false, 1, List.of(), null));
        assertTrue(permissionService.hasPermission(7L, "listing.read"));
        assertFalse(permissionService.hasPermission(7L, "listing.delete.all"));

        when(userManagementService.authorization(7L)).thenReturn(new EffectivePermissionService.Snapshot(
                7, 4, 2, "DISABLED", false, 1, List.of(), null));
        assertFalse(permissionService.hasPermission(7L, "listing.read"));
    }
}
