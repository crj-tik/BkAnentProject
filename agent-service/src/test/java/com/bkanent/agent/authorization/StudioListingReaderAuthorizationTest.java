package com.bkanent.agent.authorization;

import com.bkanent.agent.config.AgentRagConsumerProperties;
import com.bkanent.agent.security.AgentRagRequestAuthentication;
import com.bkanent.agent.studio.StudioException;
import com.bkanent.agent.studio.StudioJson;
import com.bkanent.agent.studio.StudioListingReader;
import com.bkanent.common.authz.*;
import com.bkanent.common.listing.*;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.ListingBusinessSourceRpcService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StudioListingReaderAuthorizationTest {
    private static final long NOW = 1_800_000_000L;
    private static final String OWNER = "73";
    private static final String SECRET = "studio-listing-rag-signing-secret-at-least-32-bytes";
    private static final ListingRef REF = new ListingRef("fixture", "same-id-17");
    private static final ListingSourceSnapshot SNAPSHOT = new ListingSourceSnapshot(REF, "revision-7",
            "a".repeat(64), Instant.parse("2026-10-27T00:00:00Z"), ListingDataMode.SIMULATED, "fixture-v1",
            new ListingSnapshotFacts("fixture listing", "80", new ListingMoney("3200000", "CNY",
                    ListingMoney.Unit.YUAN), Map.of("layout", "2室1厅"), List.of("commute")));

    @Test
    void listingReadPermissionIsRequiredBeforeAnySourceRpc() throws Exception {
        ListingBusinessSourceRpcService source = mock(ListingBusinessSourceRpcService.class);
        StudioListingReader reader = reader(source);
        AgentAuthorizationScope.Entry entry = entry(List.of("agent.chat.use"));

        try (var ignored = AgentAuthorizationScope.open(entry)) {
            assertThatThrownBy(() -> reader.search(OWNER, "浦东", null, 10))
                    .isInstanceOf(AuthzException.class)
                    .extracting(failure -> ((AuthzException) failure).code())
                    .isEqualTo(AuthzErrorCode.PERMISSION_DENIED);
        }

        verifyNoInteractions(source);
    }

    @Test
    void expectedSnapshotUsesSignedStableSourceChecksAndRejectsChangedContent() throws Exception {
        ListingBusinessSourceRpcService source = mock(ListingBusinessSourceRpcService.class);
        when(source.check(any(), any(), any())).thenAnswer(call -> {
            RagAuthorizedListingBusinessCheckRequest signed = call.getArgument(0);
            RagAuthEnvelope proof = call.getArgument(1);
            AuthzResponse authorization = call.getArgument(2);
            verifySignedRead(signed, proof, authorization);
            assertThat(signed.request().contractVersion()).isEqualTo(ListingBusinessResponse.VERSION);
            assertThat(signed.request().refs()).containsExactly(new ListingSourceVersionRef(REF, "revision-7"));
            return new ListingBusinessResponse<>(ListingBusinessResponse.VERSION,
                    List.of(new ListingSnapshotCheckItem(REF, "revision-7", ListingSourceStatus.CURRENT)), null);
        });
        when(source.batchRead(any(), any(), any())).thenAnswer(call -> {
            RagAuthorizedListingBusinessBatchReadRequest signed = call.getArgument(0);
            RagAuthEnvelope proof = call.getArgument(1);
            AuthzResponse authorization = call.getArgument(2);
            verifySignedRead(signed, proof, authorization);
            assertThat(signed.request().refs()).containsExactly(new ListingSourceVersionRef(REF, null));
            return new ListingBusinessResponse<>(ListingBusinessResponse.VERSION,
                    List.of(new ListingSnapshotBatchReadItem(REF, ListingSourceStatus.CURRENT, SNAPSHOT)), null);
        });

        try (var ignored = AgentAuthorizationScope.open(entry(List.of("listing.read")))) {
            assertThatThrownBy(() -> reader(source).readExpected(OWNER,
                    List.of(new ListingSourceVersionRef(REF, "revision-7")), Map.of(REF, "b".repeat(64))))
                    .isInstanceOf(StudioException.class)
                    .satisfies(failure -> {
                        StudioException studio = (StudioException) failure;
                        assertThat(studio.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                        assertThat(studio.code()).isEqualTo("SOURCE_VERSION_CONFLICT");
                    });
        }

        verify(source, times(3)).check(any(), any(), any());
        verify(source).batchRead(any(), any(), any());
    }

    @Test
    void staleExpectedVersionStopsBeforeReadingSnapshotBody() throws Exception {
        ListingBusinessSourceRpcService source = mock(ListingBusinessSourceRpcService.class);
        when(source.check(any(), any(), any())).thenAnswer(call -> {
            RagAuthorizedListingBusinessCheckRequest signed = call.getArgument(0);
            return new ListingBusinessResponse<>(ListingBusinessResponse.VERSION,
                    List.of(new ListingSnapshotCheckItem(REF, signed.request().refs().get(0).expectedRevision(),
                            ListingSourceStatus.STALE)), null);
        });

        try (var ignored = AgentAuthorizationScope.open(entry(List.of("listing.read")))) {
            assertThatThrownBy(() -> reader(source).readExpected(OWNER,
                    List.of(new ListingSourceVersionRef(REF, "revision-6"))))
                    .isInstanceOf(StudioException.class)
                    .satisfies(failure -> assertThat(((StudioException) failure).code())
                            .isEqualTo("SOURCE_VERSION_CONFLICT"));
        }

        verify(source).check(any(), any(), any());
        verify(source, never()).batchRead(any(), any(), any());
    }

    private static StudioListingReader reader(ListingBusinessSourceRpcService source) {
        AgentRagConsumerProperties properties = new AgentRagConsumerProperties();
        properties.setVersionedAuthorizationRequired(true);
        properties.setKeyId("studio-test-key");
        properties.setSigningSecret(SECRET);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("listingBusinessSourceRpcService", source);
        return new StudioListingReader(new AgentRagRequestAuthentication(properties),
                new StudioJson(new ObjectMapper()), beans.getBeanProvider(ListingBusinessSourceRpcService.class));
    }

    private static void verifySignedRead(Object signed, RagAuthEnvelope proof, AuthzResponse authorization) {
        String requestId = signed instanceof RagAuthorizedListingBusinessCheckRequest check
                ? check.requestId() : ((RagAuthorizedListingBusinessBatchReadRequest) signed).requestId();
        RagRequestSigner.verify(proof, RagCanonicalHash.requestHash(signed), "listing-master-service",
                RagOperation.READ, requestId, null,
                new RagSigningKey("studio-test-key", "agent-service", null, Set.of(RagOperation.READ),
                        SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Instant.now());
        assertThat(proof.actorId()).isEqualTo(OWNER);
        assertThat(authorization.context().actorId()).isEqualTo(OWNER);
        assertThat(authorization.context().allowedOperations()).contains("listing.read");
    }

    private static AgentAuthorizationScope.Entry entry(List<String> permissions) throws Exception {
        KeyPair authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Clock clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
        byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String nonce = nonce((byte) 4);
        RequestBinding binding = new RequestBinding("gateway", "agent-service", "POST", "/agent/studio/v1/works",
                "/agent/studio/v1/works", List.of(), "studio-request", "studio-run", "studio-session",
                AuthzCanonicalHash.bodyHash(body), "c".repeat(64), nonce, NOW - 1, NOW + 59);
        AuthorizationSnapshot snapshot = AuthzSignature.sign(new AuthorizationSnapshot(OWNER, 4, 1, false,
                permissions, null, AuthzContract.ALGORITHM, "auth-key", null), authority.getPrivate());
        String requestHash = AuthzCanonicalHash.resolveRequestHash("verified-access-token", binding);
        AuthorizationContext context = AuthzSignature.sign(new AuthorizationContext(OWNER, 4, 2,
                AuthzCanonicalHash.snapshotHash(snapshot), snapshot.permissions(), "agent-service",
                binding.requestId(), binding.runId(), binding.sessionId(), binding.policyDigest(), requestHash,
                binding.nonce(), NOW - 1, NOW + 200, NOW + 200, AuthzContract.ALGORITHM, "auth-key", null),
                authority.getPrivate());
        AuthzFreshness freshness = AuthzSignature.sign(new AuthzFreshness(OWNER, 4, 2,
                AuthorizationStatus.ACTIVE, AuthzCanonicalHash.snapshotHash(snapshot), NOW - 1, NOW + 29,
                nonce((byte) 5), AuthzContract.ALGORITHM, "auth-key", null), authority.getPrivate());
        AuthzResponse response = new AuthzResponse(snapshot, context, freshness);
        AuthorizationSession.ResponseCorrelation correlation = new AuthorizationSession.ResponseCorrelation(
                binding.requestId(), requestHash, binding.nonce());
        AuthorizationSession session = AuthorizationSession.fromEntry(response, "agent-service", correlation,
                new AuthorizationCache(8, 1_048_576, Duration.ofMinutes(1), 8),
                Map.of("auth-key", authority.getPublic()), clock);
        return new AgentAuthorizationScope.Entry(response, binding, session);
    }

    private static String nonce(byte fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, fill);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
