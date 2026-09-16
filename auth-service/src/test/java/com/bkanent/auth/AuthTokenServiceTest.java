package com.bkanent.auth;

import com.bkanent.auth.config.AuthTokenProperties;
import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.service.AuthTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthTokenServiceTest {

    private AuthTokenService authTokenService;
    private UserAccountEntity account;

    @BeforeEach
    void setUp() {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setSecret("unit-test-auth-secret");
        properties.setAccessTtlSeconds(3600);
        properties.setRefreshTtlSeconds(3600);
        authTokenService = new AuthTokenService(properties);
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
        String tamperedToken = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");
        assertFalse(authTokenService.isValidAccessToken(tamperedToken));

        AuthTokenProperties expiredProperties = new AuthTokenProperties();
        expiredProperties.setSecret("unit-test-auth-secret");
        expiredProperties.setAccessTtlSeconds(0);
        AuthTokenService expiredTokenService = new AuthTokenService(expiredProperties);
        expiredTokenService.initialize();
        assertFalse(expiredTokenService.isValidAccessToken(expiredTokenService.issueAccessToken(account)));
    }

    @Test
    void revokesToken() {
        String token = authTokenService.issueAccessToken(account);

        authTokenService.revoke(token);

        assertFalse(authTokenService.isValidAccessToken(token));
    }
}
