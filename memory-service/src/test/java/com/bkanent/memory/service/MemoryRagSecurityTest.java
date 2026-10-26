package com.bkanent.memory.service;

import com.bkanent.common.rag.*;
import com.bkanent.common.memory.MemoryContextKey;
import com.bkanent.memory.config.MemoryRagProperties;
import com.bkanent.memory.rpc.MemoryRagRpcServiceImpl;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryRagSecurityTest {
    @Test
    void signedAuthResponseProvidesRealOwnerAndManagedRolesOnly() {
        var f = new MemoryRagTestSupport();
        var principal = f.security.authenticate(f.credentials);
        assertEquals(42L, principal.userId());
        assertThrows(RagException.class, () -> f.security.requireOwner(principal, "43"));
        assertThrows(RagException.class, () -> f.security.requireAdmin(MemoryRagTestSupport.principal("AGENT")));
        f.properties.setConstraintAdminRoles(Set.of());
        assertThrows(RagException.class, () -> f.security.requireAdmin(principal));
        f.properties.setConstraintReadRoles(Set.of());
        assertThrows(RagException.class, () -> f.security.requireConstraintRead(principal));
    }
    @Test
    void ownerIsAbsoluteAndThirdRoleOrInactivePrincipalCannotOperate() {
        var f = new MemoryRagTestSupport();
        for (String role : List.of("ADMIN", "BROKER")) {
            var principal = MemoryRagTestSupport.principal(role);
            assertDoesNotThrow(() -> f.security.requireOwner(principal, "42"));
            assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class, () -> f.security.requireOwner(principal, "43")).code());
        }
        f.properties.setConstraintAdminRoles(Set.of("ADMIN", "BROKER", "MANAGER"));
        for (String role : List.of("BROKER", "MANAGER")) {
            assertThrows(RagException.class, () -> f.security.requireAdmin(MemoryRagTestSupport.principal(role)));
        }
        assertThrows(RagException.class, () -> f.security.requireOwner(MemoryRagTestSupport.principal("MANAGER"), "42"));
        for (var invalid : List.of(new AuthRagPrincipal(42L,"ADMIN",false,true,Long.MAX_VALUE),
                new AuthRagPrincipal(42L,"ADMIN",true,false,Long.MAX_VALUE),new AuthRagPrincipal(42L,"ADMIN",true,true,1))) {
            assertThrows(RagException.class, () -> f.security.requireOwner(invalid,"42"));
        }
    }
    @Test
    void missingKeysUnsignedAuthAndServiceModeSearchDeny() {
        var properties = new MemoryRagProperties();
        var calls = new AtomicInteger();
        var security = new MemoryRagSecurity(properties, (c, a) -> { calls.incrementAndGet(); return MemoryRagTestSupport.principal("ADMIN"); });
        assertThrows(RagException.class, () -> security.authenticate(new RagCredentialContext("token")));
        assertEquals(0, calls.get());
        properties.setSigningSecret(MemoryRagTestSupport.MEMORY_SECRET);
        assertThrows(RagException.class, () -> security.authenticate(new RagCredentialContext("token")));
        var f = new MemoryRagTestSupport();
        var business = new MemoryRagSearchRequest(RagSearchRequest.defaults("request", "memory.preference", "query"), 5, null, null);
        assertThrows(RagException.class, () -> f.security.verifyCaller(business, "request", null, f.credentials));
    }
    @Test
    void verifiedCallerActorCannotDisagreeWithPrincipalOrBindDifferentPayload() {
        var f = new MemoryRagTestSupport();
        var caller = new MemoryRagProperties.TrustedCaller(); caller.setCallerService("agent-service"); caller.setSecret("agent-test-independent-secret-32-bytes");
        f.properties.setTrustedCallers(java.util.Map.of("agent-current", caller));
        var business = new MemoryRagSearchRequest(RagSearchRequest.defaults("request", "memory.preference", "query"), 5, null, null);
        long now = Instant.now().getEpochSecond();
        var unsigned = new RagAuthEnvelope(1, "agent-current", "memory-service", "agent-service", RagOperation.SEARCH, "request", RagCanonicalHash.requestHash(business), RagActorMode.USER, "99", now, now + 60, RagCanonicalHash.credentialDigest(f.credentials), null);
        var signed = RagRequestSigner.sign(unsigned, caller.getSecret().getBytes(StandardCharsets.UTF_8));
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class, () -> f.security.verifyCaller(business, "request", signed, f.credentials)).code());
    }
    @Test
    void defaultsAndNullAllowedSetsRemainFailClosed() {
        var properties = new MemoryRagProperties();
        assertTrue(properties.getSemanticScoringVersions().isEmpty());
        assertTrue(properties.getConstraintReadRoles().isEmpty());
        assertTrue(properties.getConstraintAdminRoles().isEmpty());
        assertTrue(properties.getTrustedCallers().isEmpty());
        assertNull(properties.getSigningSecret());
        assertNull(properties.getAuthSigningSecret());
        assertFalse(properties.isDispatcherEnabled());
        var security = new MemoryRagSecurity(properties, (c, a) -> { fail("no principal RPC"); return null; });
        var principal = MemoryRagTestSupport.principal("ADMIN");
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class, () -> security.requireConstraintRead(principal)).code());
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class, () -> security.requireAdmin(principal)).code());
        properties.setSemanticScoringVersions(Set.of("test-only-version"));
        properties.setConstraintReadRoles(Set.of("ADMIN"));
        properties.setConstraintAdminRoles(Set.of("ADMIN"));
        properties.setSemanticScoringVersions(null);
        properties.setConstraintReadRoles(null);
        properties.setConstraintAdminRoles(null);
        assertTrue(properties.getSemanticScoringVersions().isEmpty());
        assertTrue(properties.getConstraintReadRoles().isEmpty());
        assertTrue(properties.getConstraintAdminRoles().isEmpty());
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class, () -> security.requireConstraintRead(principal)).code());
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class, () -> security.requireAdmin(principal)).code());
    }

    @Test
    void versionedScopesCarryAuthEvidenceButNeverWidenPersonalMemoryForBusinessAdmins() {
        var f = new MemoryRagTestSupport();
        f.properties.setVersionedAuthorizationRequired(true);
        long now = Instant.now().getEpochSecond();
        var personalRequest = RagSearchRequest.defaults("versioned-memory", "memory.preference", "q");
        var personalPrincipal = versionedPrincipal(now, true, List.of(), List.of("memory.search"));
        var personal = f.security.scope(personalRequest, personalPrincipal);

        assertTrue(personal.versionedAuthorization());
        assertEquals("memory.search", personal.operationCode());
        assertEquals(17, personal.permissionRevision());
        assertEquals(4, personal.credentialEpoch());
        assertTrue(personal.businessAdmin());
        assertFalse(personal.businessWideRead());
        assertTrue(personal.activeOnly());
        assertEquals("42", personal.ownerId());
        assertEquals(personalPrincipal, personal.authPrincipal());
        assertThrows(RagException.class, () -> f.security.scope(personalRequest,
                versionedPrincipal(now, true, List.of(), List.of("rag.space.manage"))));

        var constraintRequest = RagSearchRequest.defaults("versioned-constraint", "memory.constraint", "q");
        var constraintPrincipal = versionedPrincipal(now, true, List.of(), List.of("memory.constraint.read"));
        var constraint = f.security.scope(constraintRequest, constraintPrincipal);
        assertEquals("memory.constraint.read", constraint.operationCode());
        assertTrue(constraint.businessAdmin());
        assertFalse(constraint.businessWideRead());
        assertTrue(constraint.activeOnly());
        assertNull(constraint.ownerId());
        assertEquals(constraintPrincipal, constraint.authPrincipal());
    }

    private static AuthRagPrincipal versionedPrincipal(long now, boolean admin, List<String> roles,
                                                        List<String> permissions) {
        var facts = new AuthRagPrincipal(42L, null, true, false, now + 300,
                AuthRagPrincipal.VERSIONED_AUTHORIZATION_VERSION, 17, 4, admin, roles, permissions, null);
        var proof = RagRequestSigner.sign(new RagAuthEnvelope(1, "auth-response", "memory-service",
                "auth-service", RagOperation.RESOLVE_PRINCIPAL_RESPONSE, "versioned-principal",
                RagCanonicalHash.principalHash(facts), RagActorMode.USER, "42", now, now + 60,
                "test-credential-digest", null), MemoryRagTestSupport.AUTH_SECRET.getBytes(StandardCharsets.UTF_8));
        return facts.withAttestation(proof);
    }

    private MemoryRagProperties.TrustedCaller caller(MemoryRagTestSupport f) {
        var caller = new MemoryRagProperties.TrustedCaller();
        caller.setCallerService("agent-service");
        caller.setSecret("agent-test-independent-secret-32-bytes");
        f.properties.setTrustedCallers(Map.of("agent-current", caller));
        return caller;
    }

    private RagAuthEnvelope signed(MemoryRagSearchRequest business, MemoryRagTestSupport f,
                                   MemoryRagProperties.TrustedCaller caller, RagActorMode mode, String actor) {
        long now = Instant.now().getEpochSecond();
        var envelope = new RagAuthEnvelope(1, "agent-current", "memory-service", "agent-service", RagOperation.SEARCH,
                business.search().requestId(), RagCanonicalHash.requestHash(business), mode, actor, now, now + 60,
                mode == RagActorMode.USER ? RagCanonicalHash.credentialDigest(f.credentials) : null, null);
        return RagRequestSigner.sign(envelope, caller.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rpcDirectBoundaryDelegatesSignedUserRequestWithResolvedPrincipal() {
        var f = new MemoryRagTestSupport();
        var caller = caller(f);
        var business = new MemoryRagSearchRequest(RagSearchRequest.defaults("rpc", "memory.preference", "query"), 5, .7, 30.0);
        var facade = mock(MemoryRagSearchFacade.class);
        var expected = new MemoryRagSearchResponse(MemoryRagTestSupport.response(business.search(), List.of()), List.of(), MemoryRagSearchFacade.SCORING);
        when(facade.search(eq(business), any(AuthRagPrincipal.class), same(f.credentials))).thenAnswer(invocation -> {
            AuthRagPrincipal principal = invocation.getArgument(1);
            assertEquals(42L, principal.userId());
            assertEquals("ADMIN", principal.roleCode());
            assertNotNull(principal.attestation());
            return expected;
        });
        var rpc = new MemoryRagRpcServiceImpl(f.security, facade);
        assertSame(expected, rpc.search(business, signed(business, f, caller, RagActorMode.USER, "42"), f.credentials));
        verify(facade, times(1)).search(eq(business), any(AuthRagPrincipal.class), same(f.credentials));
        verifyNoMoreInteractions(facade);
    }

    @Test
    void rpcDirectBoundaryRejectsTamperedDomainOptionsBeforeFacade() {
        var f = new MemoryRagTestSupport();
        var caller = caller(f);
        var search = RagSearchRequest.defaults("rpc", "memory.preference", "query");
        var business = new MemoryRagSearchRequest(search, 5, .7, 30.0);
        var authentication = signed(business, f, caller, RagActorMode.USER, "42");
        var facade = mock(MemoryRagSearchFacade.class);
        var rpc = new MemoryRagRpcServiceImpl(f.security, facade);
        var tampered = List.of(new MemoryRagSearchRequest(search, 6, .7, 30.0),
                new MemoryRagSearchRequest(search, 5, .8, 30.0), new MemoryRagSearchRequest(search, 5, .7, 31.0));
        for (var changed : tampered) {
            assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class, () -> rpc.search(changed, authentication, f.credentials)).code());
        }
        verifyNoInteractions(facade);
    }

    @Test
    void rpcDirectBoundaryRejectsServiceActorWrongUserAndDifferentCredentialsBeforeFacade() {
        var f = new MemoryRagTestSupport();
        var caller = caller(f);
        var business = new MemoryRagSearchRequest(RagSearchRequest.defaults("rpc", "memory.preference", "query"), 5, null, null);
        var facade = mock(MemoryRagSearchFacade.class);
        var rpc = new MemoryRagRpcServiceImpl(f.security, facade);
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class, () -> rpc.search(business,
                signed(business, f, caller, RagActorMode.SERVICE, "agent-service"), f.credentials)).code());
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class, () -> rpc.search(business,
                signed(business, f, caller, RagActorMode.USER, "99"), f.credentials)).code());
        var signed = signed(business, f, caller, RagActorMode.USER, "42");
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class, () -> rpc.search(business,
                signed, new RagCredentialContext("different-test-token"))).code());
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class, () -> rpc.search(business, null, f.credentials)).code());
        verifyNoInteractions(facade);
    }

    @Test
    void authRequestUsesMemoryIdentityNotSharedRagKey() {
        var f = new MemoryRagTestSupport();
        var security = new MemoryRagSecurity(f.properties, (cred, request) -> {
            assertEquals("memory-service", request.callerService());
            assertEquals("memory-service", request.actorId());
            assertEquals("memory-current", request.keyId());
            assertEquals(RagActorMode.SERVICE, request.actorMode());
            assertEquals(RagCanonicalHash.credentialDigest(cred), request.requestHash());
            return MemoryRagTestSupport.principal("ADMIN");
        });
        assertThrows(RagException.class, () -> security.authenticate(f.credentials));
    }

    @Test
    void candidateCallbackRequiresItsDedicatedKeyAndExactSignedSearchScope() {
        var f = new MemoryRagTestSupport();
        f.properties.setCandidateAuthorizationKeyId("rag-candidate-callback");
        f.properties.setCandidateAuthorizationSecret("dedicated-test-callback-secret-with-32-bytes");
        var search = new RagSearchRequest(RagContract.VERSION, "candidate-search",
                MemoryRagSpaceIds.spaceId(MemoryRagRecordType.EXPERIENCE, "marketing", "marketing-agent"),
                "find similar result", List.of(), new RagRecallOptions(true, null, 2, 2),
                new RagFusionOptions(null, null, null, null), true, 2, 2, RagFailurePolicy.STRICT).normalized();
        var reference = new RagCandidateReference("experience-doc", "memory_experience", "experience-1", 3,
                "memory-v2.1", "a".repeat(64));
        var request = new RagCandidateAuthorizationRequest(RagContract.VERSION, search, List.of(reference));
        var context = new MemoryContextKey("42", "marketing", "marketing-agent", "conversation-1");
        var scope = f.security.memoryScope(search, context, MemoryRagRecordType.EXPERIENCE, null, List.of(), null);
        long now = Instant.now().getEpochSecond();
        var unsigned = new RagAuthEnvelope(RagContract.VERSION, "rag-candidate-callback", "memory-service", "rag-service",
                RagOperation.AUTHORIZE_CANDIDATES, search.requestId(), RagCanonicalHash.requestHash(request),
                RagActorMode.SERVICE, "rag-service", now, now + 5, null, null);
        var authentication = RagRequestSigner.sign(unsigned,
                f.properties.getCandidateAuthorizationSecret().getBytes(StandardCharsets.UTF_8));

        assertDoesNotThrow(() -> f.security.verifyCandidateCaller(request, authentication, scope));

        var wrongOperation = RagRequestSigner.sign(new RagAuthEnvelope(RagContract.VERSION,
                "rag-candidate-callback", "memory-service", "rag-service", RagOperation.MEMORY_SEARCH,
                search.requestId(), RagCanonicalHash.requestHash(request), RagActorMode.SERVICE, "rag-service",
                now, now + 5, null, null), f.properties.getCandidateAuthorizationSecret().getBytes(StandardCharsets.UTF_8));
        assertThrows(RagException.class, () -> f.security.verifyCandidateCaller(request, wrongOperation, scope));

        var changedSearch = new RagSearchRequest(search.contractVersion(), search.requestId(), search.spaceId(),
                "different query", search.filters(), search.recall(), search.fusion(), search.semanticRerank(),
                search.rerankCandidates(), search.candidateLimit(), search.failurePolicy());
        var changedRequest = new RagCandidateAuthorizationRequest(RagContract.VERSION, changedSearch, List.of(reference));
        assertThrows(RagException.class, () -> f.security.verifyCandidateCaller(changedRequest, authentication, scope));
    }
}
