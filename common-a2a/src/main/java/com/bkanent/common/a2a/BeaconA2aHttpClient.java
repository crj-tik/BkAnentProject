package com.bkanent.common.a2a;

import com.bkanent.common.rag.*;
import io.a2a.http.*;
import io.a2a.util.Utils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One official transport decorator; GET/Card discovery never receives task credentials. */
public final class BeaconA2aHttpClient implements A2AHttpClient {
    public static final String BEACON_HEADER = "X-A2A-Beacon";
    public static final String BINDING_HEADER = "X-A2A-Binding";
    public record Identity(String actorId, String audience, RagCredentialContext credentials, long expiresAtEpochSecond) {
        public Identity(String actorId, String audience, RagCredentialContext credentials) {
            this(actorId, audience, credentials, java.time.Instant.now().getEpochSecond() + 300);
        }
        @Override public String toString() { return "A2aIdentity[REDACTED]"; }
    }
    private final A2AHttpClient delegate;
    private final A2aBeaconAuthentication auth;
    private final Supplier<Identity> identity;
    public BeaconA2aHttpClient(A2AHttpClient delegate, A2aBeaconAuthentication auth, Supplier<Identity> identity) {
        this.delegate = delegate; this.auth = auth; this.identity = identity;
    }
    @Override public GetBuilder createGet() { return delegate.createGet(); }
    @Override public PostBuilder createPost() {
        PostBuilder post = delegate.createPost();
        return new PostBuilder() {
            private String body;
            @Override public PostBuilder url(String value) { post.url(value); return this; }
            @Override public PostBuilder addHeader(String name, String value) { post.addHeader(name, value); return this; }
            @Override public PostBuilder body(String value) { body = value; post.body(value); return this; }
            private void authenticate() {
                try {
                    Identity current = identity.get();
                    var node = Utils.OBJECT_MAPPER.readTree(body);
                    String method = node.path("method").asText();
                    String task = method.startsWith("message/") ? node.path("params").path("message").path("taskId").asText()
                            : node.path("params").path("id").asText();
                    A2aBeaconBinding binding = new A2aBeaconBinding(1, node.path("id").asText(), auth.service(),
                            current.audience(), current.actorId(), method, bodyHash(body), task, current.expiresAtEpochSecond());
                    A2aBeacon beacon = auth.issue(binding, current.credentials(), current.expiresAtEpochSecond());
                    if (beacon == null || beacon.value() == null) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
                    post.addHeader(BEACON_HEADER, beacon.value());
                    post.addHeader(BINDING_HEADER, Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(Utils.OBJECT_MAPPER.writeValueAsBytes(binding)));
                } catch (RagException ex) { throw ex; }
                catch (Exception ex) { throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }
            }
            @Override public A2AHttpResponse post() throws IOException, InterruptedException { authenticate(); return post.post(); }
            @Override public CompletableFuture<Void> postAsyncSSE(Consumer<String> messages, Consumer<Throwable> errors,
                    Runnable complete) throws IOException, InterruptedException { authenticate(); return post.postAsyncSSE(messages, errors, complete); }
        };
    }
    public static String bodyHash(String body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }
    }
}
