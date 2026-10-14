package com.bkanent.rag.security;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagPrincipalRpcService;
import com.bkanent.rag.config.*;
import com.bkanent.rag.rerank.RerankProfile;
import com.bkanent.rag.storage.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RagAccessControlTest {
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String CALLER_SECRET = "C".repeat(32), OWNER_SECRET = "O".repeat(32), AUTH_SECRET = "A".repeat(32), RESPONSE_SECRET = "R".repeat(32);
    private final RagCredentialContext credentials = new RagCredentialContext("verified-access-token");
    private final AtomicInteger authCalls = new AtomicInteger();
    private String responseAudience = "rag-service";
    private String responseActor = "7";
    private String responseSecret = RESPONSE_SECRET;
    private boolean inactive = false;
    private boolean inactiveRole = false;
    private RagPrincipalRpcService principalResolver;
    private final RagSecurityProperties properties = new RagSecurityProperties(Map.of(
            "consumer-current", new RagSecurityProperties.TrustedKey("agent-service", null, EnumSet.allOf(RagOperation.class), CALLER_SECRET),
            "owner-current", new RagSecurityProperties.TrustedKey("memory-service", "memory", EnumSet.allOf(RagOperation.class), OWNER_SECRET)),
            "rag-current", AUTH_SECRET, "auth-response", RESPONSE_SECRET);

    private RagAccessControl access(String id, String role, boolean signedResponse) {
        return access(space(id), role, signedResponse);
    }
    private RagAccessControl access(RagSpaceDefinition definition, String role, boolean signedResponse) {
        String id = definition.spaceId();
        ManagedRagSpaceRegistry registry = new ManagedRagSpaceRegistry(new RagProperties(Map.of(id, definition)), List::of, CLOCK);
        RagPrincipalRpcService auth = (credential, request) -> {
            authCalls.incrementAndGet();
            assertThat(request.actorMode()).isEqualTo(RagActorMode.USER);
            assertThat(request.audience()).isEqualTo("auth-service");
            String digest = RagCanonicalHash.credentialDigest(credential);
            RagRequestSigner.verify(request, digest, "auth-service", RagOperation.RESOLVE_PRINCIPAL, request.requestId(), credential,
                    new RagSigningKey("rag-current", "rag-service", null, Set.of(RagOperation.RESOLVE_PRINCIPAL), RagSecurityProperties.bytes(AUTH_SECRET)), NOW);
            AuthRagPrincipal facts = new AuthRagPrincipal(7L, role, !inactive, !inactiveRole, NOW.plusSeconds(300).getEpochSecond());
            RagAuthEnvelope attestation = signedResponse ? RagRequestSigner.sign(new RagAuthEnvelope(1, "auth-response", responseAudience, "auth-service",
                    RagOperation.RESOLVE_PRINCIPAL_RESPONSE, request.requestId(), RagCanonicalHash.principalHash(facts), RagActorMode.USER,
                    responseActor, NOW.getEpochSecond(), NOW.plusSeconds(60).getEpochSecond(), digest, null), RagSecurityProperties.bytes(responseSecret)) : null;
            return new AuthRagPrincipal(7L, role, !inactive, !inactiveRole, facts.expiresAtEpochSecond(), attestation);
        };
        principalResolver = auth;
        return new RagAccessControl(registry, properties, () -> auth, CLOCK);
    }
    private RagAuthEnvelope envelope(Object request, RagOperation operation, String requestId, RagActorMode actorMode, String key) {
        boolean owner = key.equals("owner-current");
        return RagRequestSigner.sign(new RagAuthEnvelope(1, key, "rag-service", owner ? "memory-service" : "agent-service", operation,
                requestId, RagCanonicalHash.requestHash(request), actorMode, actorMode == RagActorMode.USER ? "7" : owner ? "memory-service" : "agent-service",
                NOW.getEpochSecond(), NOW.plusSeconds(60).getEpochSecond(), actorMode == RagActorMode.USER ? RagCanonicalHash.credentialDigest(credentials) : null, null),
                RagSecurityProperties.bytes(owner ? OWNER_SECRET : CALLER_SECRET));
    }
    private RagAuthorizationScope scope(RagSearchRequest request, String owner, List<String> resources, Boolean active, String key) {
        boolean ownerKey = key.equals("owner-current");
        return RagScopeSigner.sign(new RagAuthorizationScope(1, key, ownerKey ? "memory-service" : "agent-service", "memory",
                request.spaceId(), "7", RagOperation.SEARCH, RagCanonicalHash.requestHash(request), "policy-v1", NOW.getEpochSecond(),
                NOW.plusSeconds(60).getEpochSecond(), owner, resources, active, null), RagSecurityProperties.bytes(ownerKey ? OWNER_SECRET : CALLER_SECRET));
    }

    @Test void preferenceAlwaysGetsResolvedOwnerAndActiveFilters() {
        RagSearchRequest request = RagSearchRequest.defaults("req", "memory.preference", "deployment");
        List<RagFilter> mandatory = access(request.spaceId(), "BROKER", true).search(request,
                envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current"), credentials,
                scope(request, "7", List.of(), true, "owner-current"));
        assertThat(mandatory).containsExactly(new RagFilter("userId", RagFilterOperator.EQ, RagValue.string("7")),
                new RagFilter("active", RagFilterOperator.EQ, RagValue.bool(true)));
        assertThat(authCalls.get()).isEqualTo(1);
    }
    @Test void preferenceCrossUserScopeFails() {
        RagSearchRequest request = RagSearchRequest.defaults("req", "memory.preference", "query");
        denied(() -> access(request.spaceId(), "BROKER", true).search(request,
                envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current"), credentials,
                scope(request, "8", List.of(), true, "owner-current")), RagErrorCode.FORBIDDEN);
    }
    @Test void consumerCannotSignOwnerAuthority() {
        RagSearchRequest request = RagSearchRequest.defaults("req", "memory.preference", "query");
        denied(() -> access(request.spaceId(), "BROKER", true).search(request,
                envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current"), credentials,
                scope(request, "7", List.of(), true, "consumer-current")), RagErrorCode.FORBIDDEN);
    }
    @Test void missingAuthAttestationFailsClosed() {
        RagSearchRequest request = RagSearchRequest.defaults("req", "memory.preference", "query");
        denied(() -> access(request.spaceId(), "BROKER", false).search(request,
                envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current"), credentials,
                scope(request, "7", List.of(), true, "owner-current")), RagErrorCode.AUTHENTICATION_FAILED);
    }
    @Test void forgedAuthResponseAudienceActorAndSignatureAreRejected() {
        RagSearchRequest request = RagSearchRequest.defaults("req", "memory.preference", "query");
        var access = access(request.spaceId(), "BROKER", true);
        var signed = envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current");
        responseAudience = "listing-master-service";
        denied(() -> access.search(request, signed, credentials, scope(request, "7", List.of(), true, "owner-current")), RagErrorCode.AUTHENTICATION_FAILED);
        responseAudience = "rag-service"; responseActor = "8";
        denied(() -> access.search(request, signed, credentials, scope(request, "7", List.of(), true, "owner-current")), RagErrorCode.AUTHENTICATION_FAILED);
        responseActor = "7"; responseSecret = "forged-secret".repeat(4);
        denied(() -> access.search(request, signed, credentials, scope(request, "7", List.of(), true, "owner-current")), RagErrorCode.AUTHENTICATION_FAILED);
    }
    @Test void inactiveAccountCannotAcquireRoleAuthority() {
        inactive = true;
        RagSearchRequest request = RagSearchRequest.defaults("req", "memory.preference", "query");
        denied(() -> access(request.spaceId(), "BROKER", true).search(request,
                envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current"), credentials,
                scope(request, "7", List.of(), true, "owner-current")), RagErrorCode.AUTHENTICATION_FAILED);
    }
    @Test void expiredRequestNeverCallsAuth() {
        RagSearchRequest request = RagSearchRequest.defaults("req", "memory.preference", "query");
        RagAuthEnvelope expired = RagRequestSigner.sign(new RagAuthEnvelope(1, "consumer-current", "rag-service", "agent-service", RagOperation.SEARCH,
                "req", RagCanonicalHash.requestHash(request), RagActorMode.USER, "7", NOW.minusSeconds(60).getEpochSecond(), NOW.getEpochSecond(),
                RagCanonicalHash.credentialDigest(credentials), null), RagSecurityProperties.bytes(CALLER_SECRET));
        denied(() -> access(request.spaceId(), "BROKER", true).search(request, expired, credentials, null), RagErrorCode.AUTHENTICATION_EXPIRED);
        assertThat(authCalls.get()).isZero();
    }
    @Test void realRoleMustIntersectConfiguredRoles() {
        RagSearchRequest request = RagSearchRequest.defaults("req", "memory.preference", "query");
        denied(() -> access(request.spaceId(), "DISALLOWED", true).search(request,
                envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current"), credentials,
                scope(request, "7", List.of(), true, "owner-current")), RagErrorCode.FORBIDDEN);
    }
    @Test void serviceSearchRejectedBeforeAuthAndBackend() {
        RagSearchRequest request = RagSearchRequest.defaults("req", "memory.preference", "query");
        denied(() -> access(request.spaceId(), "BROKER", true).search(request,
                envelope(request, RagOperation.SEARCH, "req", RagActorMode.SERVICE, "owner-current"), null, null), RagErrorCode.FORBIDDEN);
        assertThat(authCalls.get()).isZero();
    }
    @Test void requestTamperAndCredentialTamperRejectedBeforeAuth() {
        RagSearchRequest request = RagSearchRequest.defaults("req", "memory.preference", "query");
        var access = access(request.spaceId(), "BROKER", true);
        var signed = envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current");
        denied(() -> access.search(RagSearchRequest.defaults("req", request.spaceId(), "tampered"), signed, credentials, null), RagErrorCode.AUTHENTICATION_FAILED);
        denied(() -> access.search(request, signed, new RagCredentialContext("another-token"), null), RagErrorCode.AUTHENTICATION_FAILED);
        assertThat(authCalls.get()).isZero();
    }
    @Test void emptyListingScopeNeverMeansUnrestricted() {
        RagSearchRequest request = RagSearchRequest.defaults("req", "listing.knowledge", "query");
        denied(() -> access(request.spaceId(), "BROKER", true).search(request,
                envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current"), credentials,
                scope(request, null, List.of(), true, "owner-current")), RagErrorCode.FORBIDDEN);
    }
    @Test void constraintRequiresExplicitActiveScope() {
        RagSearchRequest request = RagSearchRequest.defaults("req", "memory.constraint", "query");
        denied(() -> access(request.spaceId(), "BROKER", true).search(request,
                envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current"), credentials,
                scope(request, null, List.of(), false, "owner-current")), RagErrorCode.FORBIDDEN);
    }
    @Test void sharedConstraintNeedsActiveAuthorityAndRoleNotInventedUserIdField() {
        RagSearchRequest request = RagSearchRequest.defaults("req", "memory.constraint", "query");
        assertThat(access(request.spaceId(), "BROKER", true).search(request,
                envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current"), credentials,
                scope(request, null, List.of(), true, "owner-current")))
                .containsExactly(new RagFilter("active", RagFilterOperator.EQ, RagValue.bool(true)));
    }
    @Test void statusAndSubmitRequireOwnerDomainEvenWhenConsumerClaimsAllowedOperation() {
        var access = access("memory.preference", "BROKER", true);
        var status = new RagStatusRequest(1, "req", "memory.preference", "event", null);
        denied(() -> access.status(status, envelope(status, RagOperation.GET_STATUS, "req", RagActorMode.SERVICE, "consumer-current")), RagErrorCode.FORBIDDEN);
        access.status(status, envelope(status, RagOperation.GET_STATUS, "req", RagActorMode.SERVICE, "owner-current"));
    }
    @Test void adminUserNeedsResolvedAdminRole() {
        var request = new RagInspectRequest(1, "req", "memory.preference");
        denied(() -> access(request.spaceId(), "BROKER", true).admin(request, request.spaceId(), request.requestId(), RagOperation.INSPECT,
                envelope(request, RagOperation.INSPECT, "req", RagActorMode.USER, "consumer-current"), credentials), RagErrorCode.FORBIDDEN);
        access(request.spaceId(), "ADMIN", true).admin(request, request.spaceId(), request.requestId(), RagOperation.INSPECT,
                envelope(request, RagOperation.INSPECT, "req", RagActorMode.USER, "consumer-current"), credentials);
    }
    @Test void domainServiceWriterCannotUpgradeToAnyManagementOperation() {
        var inspect = new RagInspectRequest(1, "req", "memory.preference");
        var provision = new RagProvisionRequest(1, "req", "memory.preference", "schema-v1", 3);
        var replay = new RagReplayRequest(1, "req", "memory.preference", "event", null);
        var access = access(inspect.spaceId(), "ADMIN", true);
        Map<RagOperation, Object> requests = Map.of(RagOperation.INSPECT, inspect, RagOperation.PROVISION, provision, RagOperation.REPLAY, replay);
        requests.forEach((operation, request) -> denied(() -> access.admin(request, inspect.spaceId(), "req", operation,
                envelope(request, operation, "req", RagActorMode.SERVICE, "owner-current"), null), RagErrorCode.FORBIDDEN));
        assertThat(authCalls.get()).isZero();
        // The same explicitly granted caller is allowed only with a signed user token and resolved admin role.
        requests.forEach((operation, request) -> access.admin(request, inspect.spaceId(), "req", operation,
                envelope(request, operation, "req", RagActorMode.USER, "owner-current"), credentials));
        assertThat(authCalls.get()).isEqualTo(3);
    }
    @Test void emptyAdminRolesDenyEvenAResolvedAdminWithExplicitOperationGrant() {
        var original = space("memory.preference");
        var noAdmins = new RagSpaceDefinition(original.spaceId(), original.ownerDomain(), original.policyVersion(), original.schemaVersion(),
                original.elasticsearchIndex(), original.vectorSchema(), original.fields(), original.returnFields(), original.searchRoles(), Set.of(),
                original.allowedClients(), original.limits(), original.rerankProfile());
        var request = new RagInspectRequest(1, "req", original.spaceId());
        denied(() -> access(noAdmins, "ADMIN", true).admin(request, request.spaceId(), request.requestId(), RagOperation.INSPECT,
                envelope(request, RagOperation.INSPECT, "req", RagActorMode.USER, "owner-current"), credentials), RagErrorCode.FORBIDDEN);
        assertThat(authCalls.get()).isEqualTo(1);
    }
    @Test void unknownKeyAndNoPoliciesCannotFallback() {
        var request = RagSearchRequest.defaults("req", "memory.preference", "query");
        var empty = new RagSecurityProperties(null, null, null, null, null);
        var access = new RagAccessControl(new ManagedRagSpaceRegistry(new RagProperties(Map.of(request.spaceId(), space(request.spaceId()))), List::of, CLOCK), empty, () -> null, CLOCK);
        denied(() -> access.search(request, envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current"), credentials, null), RagErrorCode.AUTHENTICATION_FAILED);
    }
    private RagAccessControl listingAccess(String role) {
        var original = space("listing.knowledge");
        var definition = new RagSpaceDefinition(original.spaceId(), "listing", "listing-v2", original.schemaVersion(),
                original.elasticsearchIndex(), original.vectorSchema(), original.fields(), original.returnFields(),
                Set.of("ADMIN", "BROKER", "MANAGER"), Set.of("ADMIN", "MANAGER"),
                Map.of("agent-service", EnumSet.allOf(RagOperation.class),
                        "listing-master-service", EnumSet.allOf(RagOperation.class)), original.limits(), original.rerankProfile());
        var listingKey = new RagSecurityProperties.TrustedKey("listing-master-service", "listing",
                EnumSet.allOf(RagOperation.class), OWNER_SECRET);
        var configured = new RagSecurityProperties(Map.of(
                "consumer-current", properties.trustedKeys().get("consumer-current"),
                "listing-current", listingKey), "rag-current", AUTH_SECRET, "auth-response", RESPONSE_SECRET);
        access(definition, role, true);
        RagPrincipalRpcService resolver = principalResolver;
        return new RagAccessControl(new ManagedRagSpaceRegistry(new RagProperties(Map.of(definition.spaceId(), definition)),
                List::of, CLOCK), configured, () -> resolver, CLOCK);
    }
    private RagAuthorizationScope listingScope(RagSearchRequest request, boolean businessWide, Boolean active) {
        return RagScopeSigner.sign(new RagAuthorizationScope(1, "listing-current", "listing-master-service", "listing",
                request.spaceId(), "7", RagOperation.SEARCH, RagCanonicalHash.requestHash(request), "listing-v2",
                NOW.getEpochSecond(), NOW.plusSeconds(60).getEpochSecond(), null, List.of(), active, businessWide, null),
                RagSecurityProperties.bytes(OWNER_SECRET));
    }
    @Test void administratorBusinessScopeAllowsNonActiveListingsWithoutResourceWildcard() {
        var request = RagSearchRequest.defaults("req", "listing.knowledge", "query");
        assertThat(listingAccess("ADMIN").search(request,
                envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current"), credentials,
                listingScope(request, true, false))).isEmpty();
    }
    @Test void memberBusinessScopeAlwaysRestrictsBothRoutesToActiveListings() {
        var request = RagSearchRequest.defaults("req", "listing.knowledge", "query");
        assertThat(listingAccess("BROKER").search(request,
                envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current"), credentials,
                listingScope(request, true, true)))
                .containsExactly(new RagFilter("active", RagFilterOperator.EQ, RagValue.bool(true)));
        denied(() -> listingAccess("BROKER").search(request,
                envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current"), credentials,
                listingScope(request, true, false)), RagErrorCode.FORBIDDEN);
        denied(() -> listingAccess("ADMIN").search(request,
                envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current"), credentials,
                listingScope(request, false, false)), RagErrorCode.FORBIDDEN);
    }
    @Test void configuredLegacyManagerCannotGainBusinessReadOrManagement() {
        var request = RagSearchRequest.defaults("req", "listing.knowledge", "query");
        denied(() -> listingAccess("MANAGER").search(request,
                envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current"), credentials,
                listingScope(request, true, false)), RagErrorCode.FORBIDDEN);
        var inspect = new RagInspectRequest(1, "req", request.spaceId());
        denied(() -> listingAccess("MANAGER").admin(inspect, inspect.spaceId(), "req", RagOperation.INSPECT,
                envelope(inspect, RagOperation.INSPECT, "req", RagActorMode.USER, "consumer-current"), credentials),
                RagErrorCode.FORBIDDEN);
    }
    @Test void administratorPersonalSearchStillRequiresOwnActiveScope() {
        var request = RagSearchRequest.defaults("req", "memory.preference", "query");
        denied(() -> access(request.spaceId(), "ADMIN", true).search(request,
                envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current"), credentials,
                scope(request, "8", List.of(), true, "owner-current")), RagErrorCode.FORBIDDEN);
        denied(() -> access(request.spaceId(), "ADMIN", true).search(request,
                envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current"), credentials,
                scope(request, "7", List.of(), false, "owner-current")), RagErrorCode.FORBIDDEN);
    }
    @Test void revokedRoleCannotSearchOrAdministerEvenWithAdministratorAttestation() {
        inactiveRole = true;
        var request = RagSearchRequest.defaults("req", "memory.preference", "query");
        denied(() -> access(request.spaceId(), "ADMIN", true).search(request,
                envelope(request, RagOperation.SEARCH, "req", RagActorMode.USER, "consumer-current"), credentials,
                scope(request, "7", List.of(), true, "owner-current")), RagErrorCode.AUTHENTICATION_FAILED);
        var inspect = new RagInspectRequest(1, "req", request.spaceId());
        denied(() -> access(request.spaceId(), "ADMIN", true).admin(inspect, inspect.spaceId(), "req", RagOperation.INSPECT,
                envelope(inspect, RagOperation.INSPECT, "req", RagActorMode.USER, "consumer-current"), credentials),
                RagErrorCode.AUTHENTICATION_FAILED);
    }
    private static void denied(Runnable action, RagErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(RagException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
    public static RagSpaceDefinition space(String id) {
        var field = new RagSpaceDefinition.FilterField(RagValueType.STRING, Set.of(RagFilterOperator.EQ, RagFilterOperator.IN), true);
        return new RagSpaceDefinition(id, "memory", "policy-v1", "schema-v1", "rag_" + id.replace('.', '_'),
                new RagSpaceDefinition.VectorSchema("default", "rag_" + id.replace('.', '_'), "model-v1", 3, "id", "content", "metadata", "embedding",
                        "source_type", "source_id", "COSINE", "IVF_FLAT", "{\"nlist\":1024}"),
                "memory.constraint".equals(id)
                        ? Map.of("active", new RagSpaceDefinition.FilterField(RagValueType.BOOLEAN, Set.of(RagFilterOperator.EQ), true))
                        : Map.of("userId", field, "listingId", field, "active", new RagSpaceDefinition.FilterField(RagValueType.BOOLEAN, Set.of(RagFilterOperator.EQ), true)),
                "memory.constraint".equals(id) ? Set.of("active") : Set.of("userId", "listingId", "active"), Set.of("BROKER", "ADMIN"), Set.of("ADMIN"),
                Map.of("agent-service", EnumSet.allOf(RagOperation.class), "memory-service", EnumSet.allOf(RagOperation.class)),
                new RagSpaceDefinition.Limits(500, 500, 200, 8192, 100000, 100000, 20000, 5000, 8000),
                new RerankProfile("native", "reranker", "scoring-v1", RerankProfile.Scale.BOUNDED_0_1));
    }
}
