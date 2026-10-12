package com.bkanent.auth;

import com.bkanent.auth.config.AuthTokenProperties;
import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.service.InMemoryTokenRevocationStore;
import com.bkanent.auth.service.AuthTokenService;
import com.bkanent.auth.service.TokenRevocationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthTokenServiceTest {

    private AuthTokenService authTokenService;
    private UserAccountEntity account;
    private TokenRevocationStore revocationStore;

    @BeforeEach
    void setUp() {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setSecret("unit-test-auth-secret");
        properties.setAccessTtlSeconds(3600);
        properties.setRefreshTtlSeconds(3600);
        revocationStore = new InMemoryTokenRevocationStore();
        authTokenService = new AuthTokenService(properties, revocationStore);
        authTokenService.initialize();

        account = new UserAccountEntity();
        account.setId(7L);
    }

    @Test
    void issuesAndValidatesAccessToken() {
        String token = authTokenService.issueAccessToken(account);

        assertNotNull(token);
        assertTrue(authTokenService.isValidAccessToken(token));
        assertFalse(authTokenService.isValidAccessToken(authTokenService.issueRefreshToken(account)));
    }

    @Test
    void rejectsTamperedAndExpiredTokens() {
        String token = authTokenService.issueAccessToken(account);
        int payloadStart = token.indexOf('.') + 1;
        String tamperedToken = token.substring(0, payloadStart)
                + (token.charAt(payloadStart) == 'A' ? 'B' : 'A')
                + token.substring(payloadStart + 1);
        assertFalse(authTokenService.isValidAccessToken(tamperedToken));

        AuthTokenProperties expiredProperties = new AuthTokenProperties();
        expiredProperties.setSecret("unit-test-auth-secret");
        expiredProperties.setAccessTtlSeconds(0);
        AuthTokenService expiredTokenService = new AuthTokenService(expiredProperties, new InMemoryTokenRevocationStore());
        expiredTokenService.initialize();
        assertFalse(expiredTokenService.isValidAccessToken(expiredTokenService.issueAccessToken(account)));
    }

    @Test
    void classifiesOnlyAuthenticallySignedExpiredAccessTokensWithoutGrantingPrincipal() {
        String access = authTokenService.issueAccessToken(account);
        assertFalse(authTokenService.isExpiredAccessToken(access));
        AuthTokenProperties expiredProperties = new AuthTokenProperties();
        expiredProperties.setSecret("unit-test-auth-secret");
        expiredProperties.setAccessTtlSeconds(0);
        expiredProperties.setRefreshTtlSeconds(0);
        AuthTokenService expiredTokens = new AuthTokenService(expiredProperties, new InMemoryTokenRevocationStore());
        expiredTokens.initialize();
        String expired = expiredTokens.issueAccessToken(account);

        assertTrue(authTokenService.isExpiredAccessToken(expired));
        assertNull(authTokenService.parse(expired));
        assertFalse(authTokenService.isExpiredAccessToken(expiredTokens.issueRefreshToken(account)));
        assertFalse(authTokenService.isExpiredAccessToken("gateway-userId-7"));
        assertFalse(authTokenService.isExpiredAccessToken(null));
        assertFalse(authTokenService.isExpiredAccessToken(expired + "tampered"));
    }

    @Test
    void revokesToken() {
        String token = authTokenService.issueAccessToken(account);

        authTokenService.revoke(token);

        assertFalse(authTokenService.isValidAccessToken(token));
    }

    @Test
    void parseReturnsNoPrincipalForRevokedAccessToken() {
        String token = authTokenService.issueAccessToken(account);
        assertNotNull(authTokenService.parse(token));

        authTokenService.revoke(token);

        assertNull(authTokenService.parse(token));
    }

    @Test
    void unavailableRevocationStoreDoesNotReturnPrincipal() {
        TokenRevocationStore unavailableStore = mock(TokenRevocationStore.class);
        when(unavailableStore.isRevoked(anyString())).thenThrow(new IllegalStateException("store unavailable"));
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setSecret("unit-test-auth-secret");
        AuthTokenService instance = new AuthTokenService(properties, unavailableStore);
        instance.initialize();
        String token = instance.issueAccessToken(account);

        assertThrows(IllegalStateException.class, () -> instance.parse(token));
    }

    @Test
    void revocationIsVisibleAcrossTokenServiceInstancesUsingSharedStore() {
        String token = authTokenService.issueAccessToken(account);
        AuthTokenProperties sameSecret = new AuthTokenProperties();
        sameSecret.setSecret("unit-test-auth-secret");
        AuthTokenService anotherInstance = new AuthTokenService(sameSecret, revocationStore);
        anotherInstance.initialize();

        authTokenService.revoke(token);

        assertFalse(anotherInstance.isValidAccessToken(token));
    }
}
