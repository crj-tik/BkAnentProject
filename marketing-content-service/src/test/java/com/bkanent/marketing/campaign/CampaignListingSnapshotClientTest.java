package com.bkanent.marketing.campaign;

import com.bkanent.common.authz.*;
import com.bkanent.common.listing.*;
import com.bkanent.common.mcp.authz.McpAuthorizationContract;
import com.bkanent.common.mcp.authz.McpAuthorizationScope;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.ListingBusinessSourceRpcService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CampaignListingSnapshotClientTest {
    private static final String SECRET = "campaign-listing-test-secret-0123456789";
    private static final String KEY_ID = "marketing-read-current";

    @Test
    void realSnapshotUsesSignedContextAndExactListingRpcRequest() throws Exception {
        var listings = mock(ListingBusinessSourceRpcService.class);
        var props = new CampaignRuntimeProperties();
        var client = new CampaignListingSnapshotClient(props, new ObjectMapper().findAndRegisterModules());
        ReflectionTestUtils.setField(client, "listings", listings);
        ReflectionTestUtils.setField(client, "keyId", KEY_ID);
        ReflectionTestUtils.setField(client, "secret", SECRET);

        // Internal listings are addressed as legacy-local and stay UNKNOWN until proven REAL.
        var identity = new ListingRef("legacy-local", "55");
        var source = new ListingSourceSnapshot(identity, "rev-7", "a".repeat(64), Instant.now(),
                ListingDataMode.UNKNOWN, "legacy-local", new ListingSnapshotFacts("Listing 55", "88.0",
                new ListingMoney("2500000", "CNY", ListingMoney.Unit.YUAN), Map.of(), List.of()));
        when(listings.batchReadContext(any(), any())).thenAnswer(invocation -> {
            RagContextAuthorizedListingBusinessBatchReadRequest request = invocation.getArgument(0);
            RagAuthEnvelope envelope = invocation.getArgument(1);
            assertEquals(List.of(new ListingSourceVersionRef(identity, null)), request.request().refs());
            assertEquals(request.requestId(), envelope.requestId());
            assertEquals("42", envelope.actorId());
            assertEquals("marketing-content-service", envelope.callerService());
            assertNull(envelope.credentialDigest());
            RagRequestSigner.verify(envelope, RagCanonicalHash.requestHash(request), "listing-master-service",
                    RagOperation.READ, request.requestId(), null,
                    new RagSigningKey(KEY_ID, "marketing-content-service", null, java.util.Set.of(RagOperation.READ),
                            SECRET.getBytes(StandardCharsets.UTF_8)), Instant.now());
            return new ListingBusinessResponse<>(ListingBusinessResponse.VERSION,
                    List.of(new ListingSnapshotBatchReadItem(identity, ListingSourceStatus.CURRENT, source)), null);
        });

        try (var ignored = McpAuthorizationScope.open(context(List.of("listing.read")))) {
            var snapshot = client.read(55, com.bkanent.common.marketing.campaign.CampaignDataMode.REAL);
            assertEquals("domain-parallel-v1", snapshot.path("contractVersion").asText());
            assertEquals("rev-7", snapshot.path("revision").asText());
            assertEquals("UNKNOWN", snapshot.path("dataMode").asText());
        }
        verify(listings, times(1)).batchReadContext(any(), any());
    }

    @Test
    void simulatedInternalSnapshotIsRejectedInRealMode() throws Exception {
        var listings = mock(ListingBusinessSourceRpcService.class);
        var client = new CampaignListingSnapshotClient(new CampaignRuntimeProperties(), new ObjectMapper());
        ReflectionTestUtils.setField(client, "listings", listings);
        ReflectionTestUtils.setField(client, "keyId", KEY_ID);
        ReflectionTestUtils.setField(client, "secret", SECRET);

        var identity = new ListingRef("legacy-local", "55");
        var source = new ListingSourceSnapshot(identity, "rev-7", "a".repeat(64), Instant.now(),
                ListingDataMode.SIMULATED, "legacy-local", new ListingSnapshotFacts("Listing 55", "88.0",
                new ListingMoney("2500000", "CNY", ListingMoney.Unit.YUAN), Map.of(), List.of()));
        when(listings.batchReadContext(any(), any())).thenReturn(new ListingBusinessResponse<>(
                ListingBusinessResponse.VERSION,
                List.of(new ListingSnapshotBatchReadItem(identity, ListingSourceStatus.CURRENT, source)), null));

        try (var ignored = McpAuthorizationScope.open(context(List.of("listing.read")))) {
            var rejected = assertThrows(CampaignDomainException.class, () -> client.read(55,
                    com.bkanent.common.marketing.campaign.CampaignDataMode.REAL));
            assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, rejected.status());
        }
    }

    @Test
    void realSnapshotIsRejectedBeforeRpcWithoutListingReadOperation() throws Exception {
        var listings = mock(ListingBusinessSourceRpcService.class);
        var client = new CampaignListingSnapshotClient(new CampaignRuntimeProperties(), new ObjectMapper());
        ReflectionTestUtils.setField(client, "listings", listings);
        ReflectionTestUtils.setField(client, "keyId", KEY_ID);
        ReflectionTestUtils.setField(client, "secret", SECRET);
        try (var ignored = McpAuthorizationScope.open(context(List.of("marketing.campaign.prepare")))) {
            var denied = assertThrows(CampaignDomainException.class, () -> client.read(55,
                    com.bkanent.common.marketing.campaign.CampaignDataMode.REAL));
            assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, denied.status());
        }
        verifyNoInteractions(listings);
    }

    private McpAuthorizationContract.Verified context(List<String> operations) throws Exception {
        long now = Instant.now().getEpochSecond();
        String hash = "b".repeat(64);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        KeyPair authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var unsignedContext = new AuthorizationContext("42", 7, 2, hash, operations, "agent-service",
                "parent-request", "run-55", "session-55", hash, hash, nonce,
                now - 1, now + 60, now + 90, Map.of("BROKER", 900000002L),
                AuthzContract.ALGORITHM, "auth-test", null);
        var signedContext = AuthzSignature.sign(unsignedContext, authority.getPrivate());
        var unsignedFreshness = new AuthzFreshness("42", 7, 2, AuthorizationStatus.ACTIVE,
                hash, now - 1, now + 20, nonce, AuthzContract.ALGORITHM, "auth-test", null);
        var freshness = AuthzSignature.sign(unsignedFreshness, authority.getPrivate());
        String operation = operations.get(0);
        var binding = new DelegationBinding("agent-service", "marketing-content-service", "42", List.of(operation),
                "run-55", null, "child-request", hash, hash, nonce, now - 1, now + 30);
        return new McpAuthorizationContract.Verified("42", "agent-service", "marketing-content-service", operation,
                "child-request", "campaignListingRead", signedContext, freshness, binding);
    }
}
