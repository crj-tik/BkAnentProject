package com.bkanent.gateway.filter;

import com.bkanent.common.authz.*;
import com.bkanent.common.rpc.AuthPrincipalContext;
import com.bkanent.common.security.ServiceIdentityCrypto;
import com.bkanent.gateway.config.GatewayAccessProperties;
import com.bkanent.gateway.config.GatewayAuthorizationProperties;
import org.apache.dubbo.config.annotation.DubboReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

class GatewayAuthorizationFilterTest {
    @TempDir Path temp;
    private GatewayAuthorizationProperties properties;
    private GatewayAuthorizationFilter filter;
    private AuthAuthorizationRpcService rpc;
    private KeyPair gatewayKey;
    private KeyPair authKey;
    private final AtomicReference<RequestBinding> receivedBinding = new AtomicReference<>();
    private final AtomicReference<String> rpcThread = new AtomicReference<>();

    @BeforeEach
    void setup() throws Exception {
        gatewayKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        authKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path pem = temp.resolve("gateway.pem");
        Files.writeString(pem, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(gatewayKey.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        properties = new GatewayAuthorizationProperties();
        properties.setEnabled(true);
        properties.setKeyId("gateway-key");
        properties.setPrivateKeyPath(pem.toString());
        properties.setAuthorityPublicKeys(Map.of("auth-key", ServiceIdentityCrypto.encodePublicKey(authKey.getPublic())));
        properties.setServiceAudiences(Map.of("agent-service", "agent-service"));
        filter = new GatewayAuthorizationFilter(properties);
        rpc = mock(AuthAuthorizationRpcService.class);
        ReflectionTestUtils.setField(filter, "authorizationRpcService", rpc);
        when(rpc.resolveRequestAuthorization(anyString(), any(), any())).thenAnswer(call -> {
            String token = call.getArgument(0);
            RequestBinding binding = call.getArgument(1);
            CallerProof proof = call.getArgument(2);
            receivedBinding.set(binding);
            rpcThread.set(Thread.currentThread().getName());
            AuthzSignature.verifyCallerProof(proof, "gateway", "auth-service",
                    AuthzRpcOperation.RESOLVE_REQUEST_AUTHORIZATION, binding.requestId(),
                    AuthzCanonicalHash.resolveRequestHash(token, binding), binding.nonce(),
                    java.util.Set.of(AuthzRpcOperation.RESOLVE_REQUEST_AUTHORIZATION),
                    Map.of("gateway-key", gatewayKey.getPublic()), Instant.now());
            return response(token, binding, binding.requestId(), "auth-key", authKey);
        });
    }

    @Test
    void forwardsExactBytesAndFullContextWithOneRpcOnBoundedElastic() {
        byte[] raw = "{ \"actorId\":999, \"sessionId\":\"forged\",\"text\":\"你好\" }\n".getBytes(StandardCharsets.UTF_8);
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        AtomicReference<byte[]> forwardedBytes = new AtomicReference<>();
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/agent/run?z=2&tag=b&tag=a&tag=a")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .header(AuthzTransportHeaders.RESPONSE_HEADER, "forged")
                .header(AuthzTransportHeaders.REQUEST_BINDING_HEADER, "forged")
                .header(AuthPrincipalContext.USER_ID_HEADER, "999")
                .header(AuthPrincipalContext.MARKER_HEADER, "forged")
                .header("X-User-Id", "999").header("X-Session-Id", "forged")
                .body(new String(raw, StandardCharsets.UTF_8)));
        filter.filter(exchange, target -> {
            forwarded.set(target);
            return DataBufferUtils.join(target.getRequest().getBody()).doOnNext(buffer -> {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                DataBufferUtils.release(buffer);
                forwardedBytes.set(bytes);
            }).then();
        }).block();
        assertArrayEquals(raw, forwardedBytes.get());
        HttpHeaders headers = forwarded.get().getRequest().getHeaders();
        assertNull(headers.getFirst(AuthPrincipalContext.USER_ID_HEADER));
        assertNull(headers.getFirst(AuthPrincipalContext.MARKER_HEADER));
        assertNull(headers.getFirst("X-User-Id"));
        assertNull(headers.getFirst("X-Session-Id"));
        assertEquals("Bearer token", headers.getFirst(HttpHeaders.AUTHORIZATION));
        AuthzResponse response = AuthzWireCodec.decodeResponse(headers.getFirst(AuthzTransportHeaders.RESPONSE_HEADER));
        RequestBinding binding = AuthzWireCodec.decodeBinding(headers.getFirst(AuthzTransportHeaders.REQUEST_BINDING_HEADER));
        assertEquals("7", response.context().actorId());
        assertEquals(receivedBinding.get(), binding);
        assertNull(binding.runId());
        assertNull(binding.sessionId());
        assertEquals(4, binding.query().size());
        assertEquals(32, Base64.getUrlDecoder().decode(binding.nonce()).length);
        assertEquals("/agent/run", binding.originalPath());
        assertEquals(binding.originalPath(), binding.targetPath());
        assertTrue(rpcThread.get().startsWith("boundedElastic-"));
        AuthzWireCodec.verifyIngress(response, binding, "token", "agent-service", "POST", "/agent/run",
                AuthzWireCodec.parseQuery("tag=a&z=2&tag=a&tag=b"), raw, Map.of("auth-key", authKey.getPublic()), Instant.now());
        assertThrows(AuthzException.class, () -> AuthzWireCodec.verifyIngress(response, binding, "token",
                "agent-service", "POST", "/agent/run", binding.query(), new byte[]{1},
                Map.of("auth-key", authKey.getPublic()), Instant.now()));
        assertThrows(AuthzException.class, () -> AuthzWireCodec.verifyIngress(response, binding, "token",
                "agent-service", "GET", "/agent/run", binding.query(), raw,
                Map.of("auth-key", authKey.getPublic()), Instant.now()));
        assertThrows(AuthzException.class, () -> AuthzWireCodec.verifyIngress(response, binding, "different-token",
                "agent-service", "POST", "/agent/run", binding.query(), raw,
                Map.of("auth-key", authKey.getPublic()), Instant.now()));
        verify(rpc, times(1)).resolveRequestAuthorization(anyString(), any(), any());
    }

    @Test
    void emptyBodyHasFixedHashAndUnknownRoutesDoNotGuess() {
        MockServerWebExchange empty = exchange(MockServerHttpRequest.get("/agent/run")
                .header(HttpHeaders.AUTHORIZATION, "token").build());
        filter.filter(empty, target -> Mono.empty()).block();
        assertEquals(AuthzCanonicalHash.bodyHash(new byte[0]), receivedBinding.get().bodyHash());
        MockServerWebExchange unknown = exchange(MockServerHttpRequest.get("/agent/run")
                .header(HttpHeaders.AUTHORIZATION, "token").build());
        unknown.getAttributes().put(GATEWAY_ROUTE_ATTR, route("lookalike", "lb://agent-service-suffix"));
        filter.filter(unknown, target -> { fail("unmapped route forwarded"); return Mono.empty(); }).block();
        assertEquals(503, unknown.getResponse().getStatusCode().value());
        verify(rpc, times(1)).resolveRequestAuthorization(anyString(), any(), any());
    }

    @Test
    void exactPublicRulesAndForgedHeadersAreCleanedInInMemoryWebClient() {
        AtomicInteger forwarded = new AtomicInteger();
        WebTestClient client = WebTestClient.bindToWebHandler(target -> {
            target.getAttributes().put(GATEWAY_ROUTE_ATTR, route("agent", "lb://agent-service"));
            return filter.filter(target, accepted -> {
                assertNull(accepted.getRequest().getHeaders().getFirst(AuthzTransportHeaders.RESPONSE_HEADER));
                assertNull(accepted.getRequest().getHeaders().getFirst(AuthPrincipalContext.MARKER_HEADER));
                forwarded.incrementAndGet();
                return accepted.getResponse().setComplete();
            });
        }).build();
        client.post().uri("/auth/refresh").header(AuthzTransportHeaders.RESPONSE_HEADER, "forged")
                .header(AuthPrincipalContext.MARKER_HEADER, "forged").exchange().expectStatus().isOk();
        client.get().uri("/auth/health").exchange().expectStatus().isOk();
        client.get().uri("/gateway/health").exchange().expectStatus().isOk();
        client.get().uri("/auth/refresh").exchange().expectStatus().isUnauthorized();
        client.post().uri("/auth/refresh-extra").exchange().expectStatus().isUnauthorized();
        client.get().uri("/auth/health-extra").exchange().expectStatus().isUnauthorized();
        client.get().uri("/actuator/health").exchange().expectStatus().isUnauthorized();
        client.get().uri("/auth/login").exchange().expectStatus().isUnauthorized();
        assertEquals(3, forwarded.get());
        verifyNoInteractions(rpc);
    }

    @Test
    void oversizedAndAmbiguousInputsRejectBeforeRpc() {
        properties.setMaxBodyBytes(8);
        for (MockServerHttpRequest request : List.of(
                MockServerHttpRequest.post("/agent/run").header(HttpHeaders.AUTHORIZATION, "token").body("123456789"),
                MockServerHttpRequest.post("/agent/run").header(HttpHeaders.AUTHORIZATION, "token")
                        .header(HttpHeaders.CONTENT_LENGTH, "99").body(""))) {
            MockServerWebExchange target = exchange(request);
            filter.filter(target, accepted -> { fail("oversize forwarded"); return Mono.empty(); }).block();
            assertEquals(413, target.getResponse().getStatusCode().value());
        }
        for (String query : List.of("userId=1&userId=2", "sessionId=1&session_id=2", "q=a+b", "q=%252F", "q=%FF", "q")) {
            assertThrows(AuthzException.class, () -> AuthzWireCodec.parseQuery(query));
        }
        MockServerWebExchange duplicate = exchange(MockServerHttpRequest.get("/agent/run?userId=1&userId=2")
                .header(HttpHeaders.AUTHORIZATION, "token").build());
        filter.filter(duplicate, accepted -> { fail("duplicate forwarded"); return Mono.empty(); }).block();
        assertEquals(400, duplicate.getResponse().getStatusCode().value());
        verifyNoInteractions(rpc);
    }

    @Test
    void typedCredentialFailureAndOutageAreNotConfused() {
        doThrow(new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED),
                new IllegalStateException("do not expose credentials"))
                .when(rpc).resolveRequestAuthorization(anyString(), any(), any());
        for (int expected : List.of(401, 503)) {
            MockServerWebExchange target = exchange(MockServerHttpRequest.get("/agent/run")
                    .header(HttpHeaders.AUTHORIZATION, "token").build());
            filter.filter(target, accepted -> { fail("failure forwarded"); return Mono.empty(); }).block();
            assertEquals(expected, target.getResponse().getStatusCode().value());
            assertFalse(target.getResponse().getBodyAsString().block().contains("credentials"));
        }
    }

    @Test
    void responseCorrelationAndWrongKidRejectWithNoFallback() {
        doAnswer(call -> response(call.getArgument(0), call.getArgument(1), "wrong-request", "auth-key", authKey))
                .when(rpc).resolveRequestAuthorization(anyString(), any(), any());
        assertResponseRejected();
        doAnswer(call -> {
            RequestBinding binding = call.getArgument(1);
            return response(call.getArgument(0), binding, binding.requestId(), "unknown-key", authKey);
        }).when(rpc).resolveRequestAuthorization(anyString(), any(), any());
        assertResponseRejected();
        KeyPair other;
        try { other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (Exception exception) { throw new AssertionError(exception); }
        doAnswer(call -> {
            RequestBinding binding = call.getArgument(1);
            return response(call.getArgument(0), binding, binding.requestId(), "auth-key", other);
        }).when(rpc).resolveRequestAuthorization(anyString(), any(), any());
        assertResponseRejected();
    }

    @Test
    void unsupportedRewriteAndBodyRoutesRejectBeforeBinding() {
        MockServerWebExchange target = exchange(MockServerHttpRequest.get("/agent/run")
                .header(HttpHeaders.AUTHORIZATION, "token").build());
        GatewayFilter rewrite = (exchange, chain) -> chain.filter(exchange.mutate()
                .request(exchange.getRequest().mutate().path("/rewritten").build()).build());
        target.getAttributes().put(GATEWAY_ROUTE_ATTR, Route.async().id("agent").uri("lb://agent-service")
                .predicate(exchange -> true).filter(rewrite).build());
        filter.filter(target, accepted -> { fail("rewrite route forwarded"); return Mono.empty(); }).block();
        assertEquals(503, target.getResponse().getStatusCode().value());
        verifyNoInteractions(rpc);
    }

    @Test
    void configurationIsOptInAndFailsClosedAndRateLimitUsesExactMethodPair() throws Exception {
        GatewayAuthorizationProperties defaults = new GatewayAuthorizationProperties();
        assertFalse(defaults.isEnabled());
        defaults.validate();
        defaults.setEnabled(true);
        assertThrows(IllegalStateException.class, defaults::validate);
        properties.setPublicRoutes(List.of(new GatewayAuthorizationProperties.PublicRoute("GET", "/actuator/**")));
        assertThrows(IllegalStateException.class, properties::validate);
        GatewayAccessProperties access = new GatewayAccessProperties();
        assertTrue(access.isWhitelisted("/auth/login-suffix"));
        access.setAuthorization(properties);
        properties.setPublicRoutes(List.of(new GatewayAuthorizationProperties.PublicRoute("POST", "/auth/refresh")));
        assertTrue(access.isWhitelisted("/auth/refresh", "POST"));
        assertFalse(access.isWhitelisted("/auth/refresh", "GET"));
        assertFalse(access.isWhitelisted("/auth/refresh-extra", "POST"));
        assertFalse(access.isWhitelisted("/auth/refresh"));
        DubboReference reference = GatewayAuthorizationFilter.class.getDeclaredField("authorizationRpcService")
                .getAnnotation(DubboReference.class);
        assertEquals("authorization", reference.group());
        assertEquals("1.0", reference.version());
        assertEquals("dubbo", reference.protocol());
        assertEquals(0, reference.retries());
        assertEquals(3000, reference.timeout());
    }

    @Test
    void explicitHttpRouteIdentitySupportsOnlyExactMappingAndConflictsReject() {
        properties.setRouteAudiences(Map.of("managed-agent", "agent-service"));
        MockServerWebExchange managed = exchange(MockServerHttpRequest.get("/agent/run")
                .header(HttpHeaders.AUTHORIZATION, "token").build());
        managed.getAttributes().put(GATEWAY_ROUTE_ATTR, route("managed-agent", "http://managed-agent:8080"));
        filter.filter(managed, target -> Mono.empty()).block();
        assertEquals("agent-service", receivedBinding.get().audience());
        MockServerWebExchange conflicting = exchange(MockServerHttpRequest.get("/agent/run")
                .header(HttpHeaders.AUTHORIZATION, "token").build());
        properties.setRouteAudiences(Map.of("agent", "auth-service"));
        filter.filter(conflicting, target -> { fail("conflicting mapping forwarded"); return Mono.empty(); }).block();
        assertEquals(503, conflicting.getResponse().getStatusCode().value());
        verify(rpc, times(1)).resolveRequestAuthorization(anyString(), any(), any());
    }

    @Test
    void unsupportedStreamingAndNonCanonicalWireFailClosed() {
        MockServerWebExchange streaming = exchange(MockServerHttpRequest.post("/agent/run")
                .header(HttpHeaders.AUTHORIZATION, "token").header(HttpHeaders.CONTENT_TYPE, "text/event-stream")
                .body("data: a\n\n"));
        filter.filter(streaming, target -> { fail("stream body forwarded"); return Mono.empty(); }).block();
        assertEquals(415, streaming.getResponse().getStatusCode().value());
        verifyNoInteractions(rpc);
        assertThrows(AuthzException.class, () -> AuthzWireCodec.decodeBinding("e30="));
        String duplicateJson = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"method\":\"POST\",\"method\":\"GET\"}".getBytes(StandardCharsets.UTF_8));
        assertThrows(AuthzException.class, () -> AuthzWireCodec.decodeBinding(duplicateJson));
        for (String path : List.of("/a/%2F/b", "/a/../b", "/a//b", "/a;b")) {
            assertThrows(AuthzException.class, () -> AuthzWireCodec.validatePath(path));
        }
    }

    @Test
    void validSignedResponseExceedingWireBudgetIsServerUnavailableNotClientError() {
        doAnswer(call -> {
            RequestBinding binding = call.getArgument(1);
            long now = Instant.now().getEpochSecond();
            List<String> permissions = java.util.stream.IntStream.range(0, AuthzContract.MAX_PERMISSIONS)
                    .mapToObj(index -> "agent." + "x".repeat(115) + String.format("%04d", index)).toList();
            AuthorizationSnapshot snapshot = AuthzSignature.sign(new AuthorizationSnapshot("7", 1, 1, false,
                    permissions, null, AuthzContract.ALGORITHM, "auth-key", null), authKey.getPrivate());
            String hash = AuthzCanonicalHash.snapshotHash(snapshot);
            AuthorizationContext context = AuthzSignature.sign(new AuthorizationContext("7", 1, 0, hash,
                    permissions, binding.audience(), binding.requestId(), null, null, binding.policyDigest(),
                    AuthzCanonicalHash.resolveRequestHash(call.getArgument(0), binding), binding.nonce(), now,
                    now + 60, now + 300, AuthzContract.ALGORITHM, "auth-key", null), authKey.getPrivate());
            AuthzFreshness freshness = AuthzSignature.sign(new AuthzFreshness("7", 1, 0, AuthorizationStatus.ACTIVE,
                    hash, now, now + 30, binding.nonce(), AuthzContract.ALGORITHM, "auth-key", null), authKey.getPrivate());
            return new AuthzResponse(snapshot, context, freshness);
        }).when(rpc).resolveRequestAuthorization(anyString(), any(), any());
        assertResponseRejected();
        verify(rpc, times(1)).resolveRequestAuthorization(anyString(), any(), any());
    }

    private void assertResponseRejected() {
        MockServerWebExchange target = exchange(MockServerHttpRequest.get("/agent/run")
                .header(HttpHeaders.AUTHORIZATION, "token").build());
        filter.filter(target, accepted -> { fail("bad response forwarded"); return Mono.empty(); }).block();
        assertEquals(503, target.getResponse().getStatusCode().value());
    }

    private AuthzResponse response(String token, RequestBinding binding, String responseId, String kid, KeyPair signer) {
        long now = Instant.now().getEpochSecond();
        AuthorizationSnapshot snapshot = AuthzSignature.sign(new AuthorizationSnapshot("7", 1, 1, false,
                List.of("agent.chat.use"), null, AuthzContract.ALGORITHM, kid, null), signer.getPrivate());
        String hash = AuthzCanonicalHash.snapshotHash(snapshot);
        AuthorizationContext context = AuthzSignature.sign(new AuthorizationContext("7", 1, 0, hash,
                snapshot.permissions(), binding.audience(), responseId, binding.runId(), binding.sessionId(),
                binding.policyDigest(), AuthzCanonicalHash.resolveRequestHash(token, binding), binding.nonce(), now,
                now + 60, now + 300, AuthzContract.ALGORITHM, kid, null), signer.getPrivate());
        AuthzFreshness freshness = AuthzSignature.sign(new AuthzFreshness("7", 1, 0, AuthorizationStatus.ACTIVE,
                hash, now, now + 30, binding.nonce(), AuthzContract.ALGORITHM, kid, null), signer.getPrivate());
        return new AuthzResponse(snapshot, context, freshness);
    }

    private MockServerWebExchange exchange(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route("agent", "lb://agent-service"));
        return exchange;
    }

    private Route route(String id, String uri) {
        return Route.async().id(id).uri(uri).predicate(exchange -> true).build();
    }
}
