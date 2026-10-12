package com.bkanent.auth;

import com.bkanent.auth.config.AuthRagRpcProperties;
import com.bkanent.auth.config.AuthTokenProperties;
import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.rpc.RagPrincipalRpcServiceImpl;
import com.bkanent.auth.service.AuthTokenService;
import com.bkanent.auth.service.InMemoryTokenRevocationStore;
import com.bkanent.auth.service.TokenRevocationStore;
import com.bkanent.auth.service.UserAccountService;
import com.bkanent.auth.service.UserManagementService;
import com.bkanent.common.rag.AuthRagPrincipal;
import com.bkanent.common.rag.RagActorMode;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagCanonicalHash;
import com.bkanent.common.rag.RagCredentialContext;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagOperation;
import com.bkanent.common.rag.RagRequestSigner;
import com.bkanent.common.rag.RagSigningKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagPrincipalRpcServiceImplTest {

    private static final String RAG_SECRET = "test-only-rag-rpc-secret-32-bytes-minimum";
    private static final String AUTH_SECRET = "test-only-auth-rpc-secret-32-bytes-minimum";
    private AuthRagRpcProperties rpcProperties;
    private AuthTokenProperties tokenProperties;
    private UserAccountService accounts;
    private UserManagementService users;
    private AuthTokenService tokens;
    private RagPrincipalRpcServiceImpl provider;
    private UserAccountEntity account;
    private RagCredentialContext credentials;

    @BeforeEach
    void setUp() {
        tokenProperties = new AuthTokenProperties();
        tokenProperties.setSecret("test-only-auth-token-secret");
        tokens = new AuthTokenService(tokenProperties, new InMemoryTokenRevocationStore());
        tokens.initialize();
        accounts = mock(UserAccountService.class);
        users = mock(UserManagementService.class);
        rpcProperties = new AuthRagRpcProperties();
        rpcProperties.setTrustedKeys(Map.of("rag-current", trusted("rag-service", RAG_SECRET)));
        rpcProperties.setResponseKeyId("auth-current");
        rpcProperties.setResponseSecret(AUTH_SECRET);
        provider = new RagPrincipalRpcServiceImpl(tokens, accounts, users, rpcProperties);
        account = new UserAccountEntity();
        account.setId(7L);
        account.setRoleCode("BROKER");
        account.setAccountStatus("ACTIVE");
        account.setDeleted(0);
        when(accounts.getById(7L)).thenReturn(account);
        when(users.isActiveRole("BROKER")).thenReturn(true);
        credentials = new RagCredentialContext(tokens.issueAccessToken(account));
    }

    @Test
    void resolvesRealIdentityAndSignsTokenBoundFactsWithoutExposingToken() {
        RagAuthEnvelope request = request(credentials, "rag-current", RAG_SECRET, "auth-service", "rag-service",
                RagOperation.RESOLVE_PRINCIPAL, RagActorMode.USER, "7", 30);
        AuthRagPrincipal principal = provider.resolveRagPrincipal(credentials, request);

        assertEquals(7L, principal.userId());
        assertEquals("BROKER", principal.roleCode());
        assertTrue(principal.accountActive());
        assertTrue(principal.roleActive());
        assertEquals(tokens.parse(credentials.accessToken()).expiresAt(), principal.expiresAtEpochSecond());
        assertNotNull(principal.attestation());
        assertTrue(principal.attestation().expiresAtEpochSecond() <= request.expiresAtEpochSecond());
        assertTrue(principal.attestation().expiresAtEpochSecond() <= principal.expiresAtEpochSecond());
        RagSigningKey responseKey = new RagSigningKey("auth-current", "auth-service", null,
                Set.of(RagOperation.RESOLVE_PRINCIPAL_RESPONSE), bytes(AUTH_SECRET));
        RagRequestSigner.verify(principal.attestation(), RagCanonicalHash.principalHash(principal), "rag-service",
                RagOperation.RESOLVE_PRINCIPAL_RESPONSE, request.requestId(), credentials, responseKey, Instant.now());
        assertFalse(principal.toString().contains(credentials.accessToken()));
        assertFalse(credentials.toString().contains(credentials.accessToken()));
        assertFalse(rpcProperties.toString().contains(RAG_SECRET));
        assertFalse(rpcProperties.toString().contains(AUTH_SECRET));
        verify(users).isActiveRole("BROKER");
    }

    @Test
    void usesCurrentDatabaseRoleInsteadOfAnyCallerRoleOrTokenSnapshot() {
        account.setRoleCode("ADMIN");
        when(users.isActiveRole("ADMIN")).thenReturn(true);
        assertEquals("ADMIN", resolve().roleCode());
        verify(users).isActiveRole("ADMIN");
    }

    @Test
    void defaultNoKeysRejectsEvenOtherwiseValidUserAndSignature() {
        rpcProperties.setTrustedKeys(Map.of());
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, this::resolve);
        verifyNoInteractions(accounts, users);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void missingResponseKeyRejectsWithoutReadingUser(String secret) {
        rpcProperties.setResponseSecret(secret);
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, this::resolve);
        verifyNoInteractions(accounts, users);
    }

    @Test
    void insufficientSecretMaterialRejectsBeforeAccountRead() {
        rpcProperties.getTrustedKeys().get("rag-current").setSecret("too-short");
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, this::resolve);
        rpcProperties.setTrustedKeys(Map.of("rag-current", trusted("rag-service", RAG_SECRET)));
        rpcProperties.setResponseSecret("too-short");
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, this::resolve);
        verifyNoInteractions(accounts, users);
    }

    @Test
    void missingResponseKeyIdRejects() {
        rpcProperties.setResponseKeyId(null);
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, this::resolve);
        verifyNoInteractions(accounts, users);
    }

    @Test
    void nullAuthenticationOrCredentialsRejects() {
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, () -> provider.resolveRagPrincipal(credentials, null));
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, () -> provider.resolveRagPrincipal(null, signed(credentials)));
        verifyNoInteractions(accounts, users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gateway", "auth-service", "listing-master-service", "userId=7"})
    void forgedServiceNameCannotOverrideKeyAuthority(String caller) {
        RagAuthEnvelope forged = request(credentials, "rag-current", RAG_SECRET, "auth-service", caller,
                RagOperation.RESOLVE_PRINCIPAL, RagActorMode.USER, "7", 30);
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, () -> provider.resolveRagPrincipal(credentials, forged));
        verifyNoInteractions(accounts, users);
    }

    @Test
    void wrongSignatureOrUnknownKeyIdRejectsBeforeAccountLookup() {
        RagAuthEnvelope forged = request(credentials, "rag-current", AUTH_SECRET, "auth-service", "rag-service",
                RagOperation.RESOLVE_PRINCIPAL, RagActorMode.USER, "7", 30);
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, () -> provider.resolveRagPrincipal(credentials, forged));
        RagAuthEnvelope unknownKey = request(credentials, "unknown", RAG_SECRET, "auth-service", "rag-service",
                RagOperation.RESOLVE_PRINCIPAL, RagActorMode.USER, "7", 30);
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, () -> provider.resolveRagPrincipal(credentials, unknownKey));
        verifyNoInteractions(accounts, users);
    }

    @Test
    void wrongAudienceOrOperationRejectsBeforeAccountLookup() {
        RagAuthEnvelope wrongAudience = request(credentials, "rag-current", RAG_SECRET, "rag-service", "rag-service",
                RagOperation.RESOLVE_PRINCIPAL, RagActorMode.USER, "7", 30);
        RagAuthEnvelope wrongOperation = request(credentials, "rag-current", RAG_SECRET, "auth-service", "rag-service",
                RagOperation.SEARCH, RagActorMode.USER, "7", 30);
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, () -> provider.resolveRagPrincipal(credentials, wrongAudience));
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, () -> provider.resolveRagPrincipal(credentials, wrongOperation));
        verifyNoInteractions(accounts, users);
    }

    @Test
    void forgedActorUserIdCannotBecomePrincipal() {
        RagAuthEnvelope forged = request(credentials, "rag-current", RAG_SECRET, "auth-service", "rag-service",
                RagOperation.RESOLVE_PRINCIPAL, RagActorMode.USER, "8", 30);
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, () -> provider.resolveRagPrincipal(credentials, forged));
        verifyNoInteractions(accounts, users);
    }

    @Test
    void serviceModeCannotResolvePrincipalWithoutCallerActorAndTokenDigest() {
        RagAuthEnvelope service = new RagAuthEnvelope(1, "rag-current", "auth-service", "rag-service",
                RagOperation.RESOLVE_PRINCIPAL, "resolve-7", RagCanonicalHash.credentialDigest(credentials),
                RagActorMode.SERVICE, null, Instant.now().getEpochSecond(), Instant.now().getEpochSecond() + 30,
                null, null);
        RagAuthEnvelope signed = RagRequestSigner.sign(service, bytes(RAG_SECRET));
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, () -> provider.resolveRagPrincipal(credentials, signed));
        verifyNoInteractions(accounts, users);
    }

    @Test
    void serviceIdentityResolvesActualTokenUserButDoesNotSupplyUserIdentity() {
        RagAuthEnvelope request = request(credentials, "rag-current", RAG_SECRET, "auth-service", "rag-service",
                RagOperation.RESOLVE_PRINCIPAL, RagActorMode.SERVICE, "rag-service", 30);
        AuthRagPrincipal principal = provider.resolveRagPrincipal(credentials, request);
        assertEquals(7L, principal.userId());
        assertEquals(RagActorMode.USER, principal.attestation().actorMode());
        assertEquals("7", principal.attestation().actorId());
        RagAuthEnvelope forged = request(credentials, "rag-current", RAG_SECRET, "auth-service", "rag-service",
                RagOperation.RESOLVE_PRINCIPAL, RagActorMode.SERVICE, "userId=8", 30);
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, () -> provider.resolveRagPrincipal(credentials, forged));
    }

    @Test
    void signatureCannotBeReusedWithAnotherTokenOrPayloadHash() {
        RagAuthEnvelope original = signed(credentials);
        RagCredentialContext refresh = new RagCredentialContext(tokens.issueRefreshToken(account));
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, () -> provider.resolveRagPrincipal(refresh, original));
        RagAuthEnvelope wrongHash = new RagAuthEnvelope(original.contractVersion(), original.keyId(), original.audience(),
                original.callerService(), original.operation(), original.requestId(), "0".repeat(64), original.actorMode(),
                original.actorId(), original.issuedAtEpochSecond(), original.expiresAtEpochSecond(),
                original.credentialDigest(), null);
        RagAuthEnvelope signedWrongHash = RagRequestSigner.sign(wrongHash, bytes(RAG_SECRET));
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, () -> provider.resolveRagPrincipal(credentials, signedWrongHash));
        verifyNoInteractions(accounts, users);
    }

    @Test
    void expiredRequestIsNotResolved() {
        RagAuthEnvelope expired = request(credentials, "rag-current", RAG_SECRET, "auth-service", "rag-service",
                RagOperation.RESOLVE_PRINCIPAL, RagActorMode.USER, "7", -1);
        assertCode(RagErrorCode.AUTHENTICATION_EXPIRED, () -> provider.resolveRagPrincipal(credentials, expired));
        verifyNoInteractions(accounts, users);
    }

    @Test
    void rotatedKeyWorksUntilExplicitlyRemoved() {
        String nextSecret = "test-only-rotated-rag-secret-32-bytes-minimum";
        rpcProperties.setTrustedKeys(Map.of("rag-current", trusted("rag-service", RAG_SECRET),
                "rag-next", trusted("rag-service", nextSecret)));
        RagAuthEnvelope next = request(credentials, "rag-next", nextSecret, "auth-service", "rag-service",
                RagOperation.RESOLVE_PRINCIPAL, RagActorMode.USER, "7", 30);
        assertEquals(7L, provider.resolveRagPrincipal(credentials, next).userId());
        rpcProperties.setTrustedKeys(Map.of("rag-next", trusted("rag-service", nextSecret)));
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, this::resolve);
    }

    @Test
    void refreshMalformedExpiredAndRevokedTokensFailClosed() {
        RagCredentialContext refresh = new RagCredentialContext(tokens.issueRefreshToken(account));
        RagCredentialContext malformed = new RagCredentialContext("gateway-userId-7");
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, () -> provider.resolveRagPrincipal(refresh, signed(refresh)));
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, () -> provider.resolveRagPrincipal(malformed, signed(malformed)));
        tokenProperties.setAccessTtlSeconds(0);
        RagCredentialContext expired = new RagCredentialContext(tokens.issueAccessToken(account));
        assertCode(RagErrorCode.AUTHENTICATION_EXPIRED, () -> provider.resolveRagPrincipal(expired, signed(expired)));
        tokens.revoke(credentials.accessToken());
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, this::resolve);
        verifyNoInteractions(accounts, users);
    }

    @Test
    void absentDeletedOrInactiveAccountsFailClosed() {
        when(accounts.getById(7L)).thenReturn(null);
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, this::resolve);
        when(accounts.getById(7L)).thenReturn(account);
        account.setDeleted(1);
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, this::resolve);
        account.setDeleted(null);
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, this::resolve);
        account.setDeleted(0);
        account.setAccountStatus("DISABLED");
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, this::resolve);
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void missingRoleCannotBeTreatedAsActive(String role) {
        account.setRoleCode(role);
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, this::resolve);
        verifyNoInteractions(users);
    }

    @Test
    void inactiveRoleAndMismatchedDatabaseIdentityFailClosed() {
        when(users.isActiveRole("BROKER")).thenReturn(false);
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, this::resolve);
        account.setId(8L);
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, this::resolve);
    }

    @Test
    void databaseOrRoleDependencyFailureCannotLeakCauseOrProducePrincipal() {
        when(accounts.getById(7L)).thenThrow(new IllegalStateException(credentials.accessToken() + AUTH_SECRET));
        RagException accountFailure = assertCode(RagErrorCode.DEPENDENCY_UNAVAILABLE, this::resolve);
        assertEquals(null, accountFailure.getCause());
        assertFalse(accountFailure.toString().contains(credentials.accessToken()));
        doReturn(account).when(accounts).getById(7L);
        when(users.isActiveRole("BROKER")).thenThrow(new IllegalStateException(RAG_SECRET));
        RagException roleFailure = assertCode(RagErrorCode.DEPENDENCY_UNAVAILABLE, this::resolve);
        assertFalse(roleFailure.toString().contains(RAG_SECRET));
    }

    @Test
    void revocationStoreFailureCannotProducePrincipalOrLeakToken() {
        TokenRevocationStore brokenStore = mock(TokenRevocationStore.class);
        when(brokenStore.isRevoked(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException(credentials.accessToken()));
        AuthTokenService brokenTokens = new AuthTokenService(tokenProperties, brokenStore);
        brokenTokens.initialize();
        provider = new RagPrincipalRpcServiceImpl(brokenTokens, accounts, users, rpcProperties);
        RagException failure = assertCode(RagErrorCode.DEPENDENCY_UNAVAILABLE, this::resolve);
        assertFalse(failure.toString().contains(credentials.accessToken()));
        verifyNoInteractions(accounts, users);
    }

    private AuthRagPrincipal resolve() {
        return provider.resolveRagPrincipal(credentials, signed(credentials));
    }

    private RagAuthEnvelope signed(RagCredentialContext context) {
        return request(context, "rag-current", RAG_SECRET, "auth-service", "rag-service",
                RagOperation.RESOLVE_PRINCIPAL, RagActorMode.USER, "7", 30);
    }

    private RagAuthEnvelope request(RagCredentialContext context, String keyId, String secret, String audience,
                                    String caller, RagOperation operation, RagActorMode mode, String actorId, int ttl) {
        long now = Instant.now().getEpochSecond();
        String digest = RagCanonicalHash.credentialDigest(context);
        long issuedAt = ttl < 0 ? now - 30 : now;
        return RagRequestSigner.sign(new RagAuthEnvelope(1, keyId, audience, caller, operation, "resolve-7", digest,
                mode, actorId, issuedAt, now + ttl, digest, null), bytes(secret));
    }

    private RagException assertCode(RagErrorCode code, org.junit.jupiter.api.function.Executable action) {
        RagException exception = assertThrows(RagException.class, action);
        assertEquals(code, exception.code());
        return exception;
    }

    @ParameterizedTest
    @ValueSource(strings = {"listing-master-service", "memory-service"})
    void managedDomainKeyResolvesFactsAndAttestationIsBoundToVerifiedCaller(String caller) {
        String domainSecret = "test-only-domain-secret-32-bytes-minimum";
        rpcProperties.getTrustedKeys().put("domain-current", trusted(caller, domainSecret));
        RagAuthEnvelope request = request(credentials, "domain-current", domainSecret, "auth-service", caller,
                RagOperation.RESOLVE_PRINCIPAL, RagActorMode.USER, "7", 30);
        AuthRagPrincipal principal = provider.resolveRagPrincipal(credentials, request);
        assertEquals(caller, principal.attestation().audience());
        RagSigningKey responseKey = new RagSigningKey("auth-current", "auth-service", null,
                Set.of(RagOperation.RESOLVE_PRINCIPAL_RESPONSE), bytes(AUTH_SECRET));
        RagRequestSigner.verify(principal.attestation(), RagCanonicalHash.principalHash(principal), caller,
                RagOperation.RESOLVE_PRINCIPAL_RESPONSE, request.requestId(), credentials, responseKey, Instant.now());
        RagAuthEnvelope impersonateRag = request(credentials, "domain-current", domainSecret, "auth-service", "rag-service",
                RagOperation.RESOLVE_PRINCIPAL, RagActorMode.USER, "7", 30);
        assertCode(RagErrorCode.AUTHENTICATION_FAILED,
                () -> provider.resolveRagPrincipal(credentials, impersonateRag));
    }

    @Test
    void unsupportedCallerEvenInManagedConfigurationCannotObtainFacts() {
        rpcProperties.getTrustedKeys().put("gateway", trusted("gateway", RAG_SECRET));
        RagAuthEnvelope request = request(credentials, "gateway", RAG_SECRET, "auth-service", "gateway",
                RagOperation.RESOLVE_PRINCIPAL, RagActorMode.USER, "7", 30);
        assertCode(RagErrorCode.AUTHENTICATION_FAILED, () -> provider.resolveRagPrincipal(credentials, request));
        verifyNoInteractions(accounts, users);
    }

    private AuthRagRpcProperties.TrustedKey trusted(String caller, String secret) {
        AuthRagRpcProperties.TrustedKey key = new AuthRagRpcProperties.TrustedKey();
        key.setCallerService(caller);
        key.setSecret(secret);
        return key;
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
