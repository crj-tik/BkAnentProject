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
    void nonCanonicalAliasesCannotBypassRevocationOrLogoutVerification() throws Exception {
        String token = authTokenService.issueAccessToken(account);
        String[] parts = token.split("\\.");
        String paddedSignature = token + "=";
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int finalIndex = alphabet.indexOf(parts[2].charAt(parts[2].length() - 1));
        String nonZeroPadBits = token.substring(0, token.length()-1) + alphabet.charAt(finalIndex + 1);
        assertTrue(java.util.Arrays.equals(java.util.Base64.getUrlDecoder().decode(parts[2]),
                java.util.Base64.getUrlDecoder().decode(nonZeroPadBits.substring(nonZeroPadBits.lastIndexOf('.')+1))));
        // Even an authentic HMAC over a padded payload is not an issued/canonical token.
        String paddedPayload = parts[0] + "." + parts[1] + "=".repeat((4-parts[1].length()%4)%4);
        if (paddedPayload.equals(parts[0]+"."+parts[1])) paddedPayload += "=";
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("unit-test-auth-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String authenticPaddedPayload = paddedPayload + "." + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(paddedPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for (String alias : java.util.List.of(paddedSignature, nonZeroPadBits, authenticPaddedPayload)) {
            assertNull(authTokenService.parse(alias));
            assertNull(authTokenService.verifyForLogout(alias));
            authTokenService.revoke(alias);
            assertNotNull(authTokenService.parse(token), "rejecting aliases must not revoke another canonical credential");
        }
        authTokenService.revoke(token);
        assertNull(authTokenService.parse(token));
        assertNull(authTokenService.parse(paddedSignature));
        assertNull(authTokenService.parse(nonZeroPadBits));
        assertNotNull(authTokenService.verifyForLogout(token), "exact revoked access may complete idempotent logout only");
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
