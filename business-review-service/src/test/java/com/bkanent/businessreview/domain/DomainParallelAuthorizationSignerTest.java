package com.bkanent.businessreview.domain;

import com.bkanent.common.authz.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DomainParallelAuthorizationSignerTest {
    private static final String AUTH_KEY_ID = "auth-current";
    private static final String SERVICE_KEY_ID = "business-review-service-current";
    private static final String AUDIENCE = "business-review-service";
    private static final String OPERATION = "business.review.read.store";
    private static final String HASH = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void signsExactDownstreamBodyWithVerifiedContextAndDedicatedServiceIdentity() throws Exception {
        KeyPair authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair service = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path privateKey = temporaryDirectory.resolve("service-identity.pem");
        Files.writeString(privateKey, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(service.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        DomainParallelAuthorizationSigner signer = new DomainParallelAuthorizationSigner(
                SERVICE_KEY_ID, privateKey.toString(), new ObjectMapper());
        byte[] body = "{\"contractVersion\":\"domain-parallel-v1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        try (AuthzRequestScope.Scope ignored = openScope(authority)) {
            String encoded = signer.authorizationHeader(body, "listing-master-service", OPERATION,
                    "POST", "/listings/business/v1/search");
            DelegatedAuthorizationRequest request = AuthzWireCodec.decodeDelegatedAuthorizationRequest(encoded);
            DelegationBinding binding = request.binding();

            assertThat(binding.callerService()).isEqualTo("business-review-service");
            assertThat(binding.audience()).isEqualTo("listing-master-service");
            assertThat(binding.operations()).containsExactly(OPERATION);
            assertThat(binding.bodyHash()).isEqualTo(AuthzCanonicalHash.bodyHash(body));
            assertThat(binding.requestId()).startsWith("dp1|POST|/listings/business/v1/search|");
            assertThat(request.context().audience()).isEqualTo(AUDIENCE);
            assertThat(request.callerProof().keyId()).isEqualTo(SERVICE_KEY_ID);
            AuthzSignature.verifyCallerProof(request.callerProof(), "business-review-service",
                    "listing-master-service", AuthzRpcOperation.VALIDATE_DELEGATION, binding.requestId(),
                    AuthzCanonicalHash.delegationRequestHash(request.context(), binding), binding.nonce(),
                    java.util.Set.of(AuthzRpcOperation.VALIDATE_DELEGATION),
                    Map.of(SERVICE_KEY_ID, service.getPublic()), Instant.now());
        }
    }

    @Test
    void missingVerifiedContextCannotMintDownstreamProof() throws Exception {
        KeyPair service = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path privateKey = temporaryDirectory.resolve("service-identity.pem");
        Files.writeString(privateKey, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(service.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        DomainParallelAuthorizationSigner signer = new DomainParallelAuthorizationSigner(
                SERVICE_KEY_ID, privateKey.toString(), new ObjectMapper());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> signer.authorizationHeader(
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), "listing-master-service", OPERATION,
                "POST", "/listings/business/v1/search"))
                .isInstanceOf(AuthzException.class);
    }

    private static AuthzRequestScope.Scope openScope(KeyPair authority) {
        long now = Instant.now().getEpochSecond();
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String token = "access-token";
        byte[] body = new byte[0];
        RequestBinding binding = new RequestBinding("gateway", AUDIENCE, "GET", "/business-review/reviews",
                "/business-review/reviews", List.of(), "request-1", null, null,
                AuthzCanonicalHash.bodyHash(body), HASH, nonce, now, now + AuthzDeadlines.REQUEST_SECONDS);
        AuthorizationSnapshot snapshot = AuthzSignature.sign(new AuthorizationSnapshot("17", 3, 1, false,
                List.of(OPERATION), null, Map.of(), AuthzContract.ALGORITHM, AUTH_KEY_ID, null), authority.getPrivate());
        String permissionHash = AuthzCanonicalHash.snapshotHash(snapshot);
        AuthorizationContext context = AuthzSignature.sign(new AuthorizationContext("17", 3, 0,
                permissionHash, List.of(OPERATION), AUDIENCE, binding.requestId(), null, null, HASH,
                AuthzCanonicalHash.resolveRequestHash(token, binding), nonce, now,
                now + AuthzDeadlines.CONTEXT_SECONDS, now + AuthzDeadlines.CONTEXT_SECONDS,
                Map.of(), AuthzContract.ALGORITHM, AUTH_KEY_ID, null), authority.getPrivate());
        AuthzFreshness freshness = AuthzSignature.sign(new AuthzFreshness("17", 3, 0,
                AuthorizationStatus.ACTIVE, permissionHash, now, now + AuthzDeadlines.FRESHNESS_SECONDS,
                nonce, AuthzContract.ALGORITHM, AUTH_KEY_ID, null), authority.getPrivate());
        AuthzResponse response = new AuthzResponse(snapshot, context, freshness);
        return AuthzRequestScope.verifyAndOpen(response, binding, token, AUDIENCE, "GET",
                "/business-review/reviews", null, body, Map.of(AUTH_KEY_ID, authority.getPublic()), Instant.now());
    }
}
