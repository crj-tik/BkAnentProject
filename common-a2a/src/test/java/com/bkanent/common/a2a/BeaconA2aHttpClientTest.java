package com.bkanent.common.a2a;

import com.bkanent.common.rag.*;
import com.bkanent.common.authz.*;
import com.bkanent.common.security.ServiceIdentityCrypto;
import io.a2a.client.A2AClient;
import io.a2a.http.*;
import io.a2a.spec.*;
import io.a2a.util.Utils;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BeaconA2aHttpClientTest {
    @Test void patchedOfficialClientUsesDedicatedHeadersForAllFiveOperationsNotCardGets() throws Exception {
        var auth = mock(A2aBeaconAuthentication.class);
        when(auth.service()).thenReturn("agent-service");
        when(auth.issue(any(), any(), anyLong())).thenReturn(new A2aBeacon("opaque-beacon", java.time.Instant.now().getEpochSecond() + 300));
        var wire = new RecordingTransport();
        var transport = new BeaconA2aHttpClient(wire, auth,
                () -> new BeaconA2aHttpClient.Identity("7", "listing-master-service", new RagCredentialContext("private-token")));
        var client = new A2AClient(transport, "https://listing.example/a2a");
        var message = new Message.Builder().role(Message.Role.USER).messageId("m").taskId("task").contextId("context")
                .parts(List.of(new TextPart("query"))).build();
        var params = new MessageSendParams(message, new MessageSendConfiguration(List.of("text"), null, null, false), new HashMap<>());
        client.sendMessage("send", params);
        client.sendStreamingMessage("stream", params, ignored -> { }, ignored -> { }, () -> { });
        client.getTask("get", new TaskQueryParams("task"));
        client.cancelTask("cancel", new TaskIdParams("task"));
        client.resubscribeToTask("resume", new TaskIdParams("task"), ignored -> { }, ignored -> { }, () -> { });
        assertThat(wire.posts).hasSize(5);
        assertThat(wire.posts).extracting(Post::method).containsExactly("message/send", "message/stream", "tasks/get", "tasks/cancel", "tasks/resubscribe");
        for (var post : wire.posts) {
            assertThat(post.headers).containsEntry(BeaconA2aHttpClient.BEACON_HEADER, "opaque-beacon");
            assertThat(post.body).doesNotContain("private-token", "opaque-beacon");
            var binding = Utils.OBJECT_MAPPER.readValue(Base64.getUrlDecoder().decode(post.headers.get(BeaconA2aHttpClient.BINDING_HEADER)), A2aBeaconBinding.class);
            assertThat(binding.actorId()).isEqualTo("7"); assertThat(binding.taskId()).isEqualTo("task");
            assertThat(binding.bodyHash()).isEqualTo(BeaconA2aHttpClient.bodyHash(post.body));
        }
        transport.createGet().url("https://listing.example/.well-known/agent.json").get();
        assertThat(wire.getHeaders).doesNotContainKeys(BeaconA2aHttpClient.BEACON_HEADER, BeaconA2aHttpClient.BINDING_HEADER);
        verify(auth, times(5)).issue(any(), any(), anyLong());
    }

    @Test void versionedTransportSignsEveryPostLocallyWithoutCallingAuth() throws Exception {
        var auth = mock(A2aBeaconAuthentication.class);
        var wire = new RecordingTransport();
        var authority = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var caller = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        long now = java.time.Instant.now().getEpochSecond();
        String permissionHash = "a".repeat(64);
        String policyDigest = "b".repeat(64);
        String contextNonce = randomNonce();
        var unsignedContext = new AuthorizationContext("7", 4, 2, permissionHash,
                List.of("agent.workflow.invoke.listing"), "agent-service", "ingress-request", "run-1", "session-1",
                policyDigest, permissionHash, contextNonce, now, now + 30, now + 120,
                AuthzContract.ALGORITHM, "auth-k1", null);
        var context = AuthzSignature.sign(unsignedContext, authority.getPrivate());
        var freshness = AuthzSignature.sign(new AuthzFreshness("7", 4, 2, AuthorizationStatus.ACTIVE,
                permissionHash, now, now + 30, contextNonce, AuthzContract.ALGORITHM, "auth-k1", null),
                authority.getPrivate());
        var identity = BeaconA2aHttpClient.Identity.versioned("listing-master-service", context, freshness,
                "agent.workflow.invoke.listing", "agent-service", "agent-k1", caller.getPrivate());
        var transport = new BeaconA2aHttpClient(wire, auth, () -> identity);
        var client = new A2AClient(transport, "https://listing.example/a2a");
        var message = new Message.Builder().role(Message.Role.USER).messageId("m").taskId("task").contextId("context")
                .parts(List.of(new TextPart("query"))).build();
        var params = new MessageSendParams(message,
                new MessageSendConfiguration(List.of("text"), null, null, false), new HashMap<>());

        client.sendMessage("send", params);
        client.sendStreamingMessage("stream", params, ignored -> { }, ignored -> { }, () -> { });
        client.getTask("get", new TaskQueryParams("task"));
        client.cancelTask("cancel", new TaskIdParams("task"));
        client.resubscribeToTask("resume", new TaskIdParams("task"), ignored -> { }, ignored -> { }, () -> { });

        assertThat(wire.posts).extracting(Post::method).containsExactly(
                "message/send", "message/stream", "tasks/get", "tasks/cancel", "tasks/resubscribe");
        for (var post : wire.posts) {
            assertThat(post.headers).containsKey(BeaconA2aHttpClient.AUTHORIZATION_HEADER)
                    .doesNotContainKeys(BeaconA2aHttpClient.BEACON_HEADER, BeaconA2aHttpClient.BINDING_HEADER);
            var envelope = AuthzWireCodec.decodeDelegatedAuthorizationRequest(
                    post.headers.get(BeaconA2aHttpClient.AUTHORIZATION_HEADER));
            assertThat(envelope.context().actorId()).isEqualTo("7");
            assertThat(envelope.binding().audience()).isEqualTo("listing-master-service");
            assertThat(envelope.binding().bodyHash()).isEqualTo(AuthzCanonicalHash.bodyHash(
                    post.body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            AuthzSignature.verifyCallerProof(envelope.callerProof(), "agent-service", "listing-master-service",
                    AuthzRpcOperation.VALIDATE_DELEGATION, envelope.binding().requestId(),
                    AuthzCanonicalHash.delegationRequestHash(envelope.context(), envelope.binding()),
                    envelope.binding().nonce(), java.util.Set.of(AuthzRpcOperation.VALIDATE_DELEGATION),
                    Map.of("agent-k1", caller.getPublic()), java.time.Instant.now());
        }
        transport.createGet().url("https://listing.example/.well-known/agent.json").get();
        verifyNoInteractions(auth);
    }
    @Test void safeOfficialJdkTransportDoesNotFollow302Or307OrForwardBeacon() throws Exception {
        var targetCalls = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/target", exchange -> {
            try (exchange) {
                exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
                targetCalls.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
            }
        });
        for (int code : List.of(302, 307)) server.createContext("/redirect" + code, exchange -> {
            try (exchange) {
                exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
                exchange.getResponseHeaders().set("Location", base + "/target");
                exchange.sendResponseHeaders(code, -1);
            }
        });
        server.start();
        try {
            var transport = new JdkA2AHttpClient(java.net.http.HttpClient.Redirect.NEVER);
            for (int code : List.of(302, 307)) {
                var response = transport.createPost().url(base + "/redirect" + code)
                        .addHeader(BeaconA2aHttpClient.BEACON_HEADER, "secret-reference").body("{}").post();
                assertThat(response.status()).isEqualTo(code);
                var errors = new java.util.concurrent.atomic.AtomicInteger();
                transport.createPost().url(base + "/redirect" + code).addHeader(BeaconA2aHttpClient.BEACON_HEADER, "secret-reference")
                        .body("{}").postAsyncSSE(ignored -> { }, ignored -> errors.incrementAndGet(), () -> { }).get(5, TimeUnit.SECONDS);
                assertThat(errors.get()).isPositive();
            }
            assertThat(targetCalls.get()).isZero();
        } finally { server.stop(0); }
    }
    private record Post(String body, String method, Map<String, String> headers) { }
    private static String randomNonce() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static final class RecordingTransport implements A2AHttpClient {
        final List<Post> posts = new ArrayList<>();
        final Map<String, String> getHeaders = new HashMap<>();
        public GetBuilder createGet() {
            return new GetBuilder() {
                public GetBuilder url(String url) { return this; }
                public GetBuilder addHeader(String name, String value) { getHeaders.put(name, value); return this; }
                public A2AHttpResponse get() { return response("{}"); }
                public CompletableFuture<Void> getAsyncSSE(Consumer<String> messages, Consumer<Throwable> errors, Runnable complete) { return CompletableFuture.completedFuture(null); }
            };
        }
        public PostBuilder createPost() {
            return new PostBuilder() {
                String body;
                final Map<String, String> headers = new HashMap<>();
                public PostBuilder url(String url) { return this; }
                public PostBuilder addHeader(String name, String value) { headers.put(name, value); return this; }
                public PostBuilder body(String value) { body = value; return this; }
                private String record() {
                    try {
                        var json = Utils.OBJECT_MAPPER.readTree(body);
                        posts.add(new Post(body, json.path("method").asText(), Map.copyOf(headers)));
                        return "{\"jsonrpc\":\"2.0\",\"id\":" + json.get("id") + ",\"result\":{\"kind\":\"task\",\"id\":\"task\",\"contextId\":\"context\",\"status\":{\"state\":\"completed\"}}}";
                    } catch (Exception failure) { throw new AssertionError(failure); }
                }
                public A2AHttpResponse post() { return response(record()); }
                public CompletableFuture<Void> postAsyncSSE(Consumer<String> messages, Consumer<Throwable> errors, Runnable complete) { record(); return CompletableFuture.completedFuture(null); }
            };
        }
        private static A2AHttpResponse response(String body) {
            return new A2AHttpResponse() {
                public int status() { return 200; } public boolean success() { return true; } public String body() { return body; }
            };
        }
    }
}
