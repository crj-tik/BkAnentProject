package com.bkanent.common.a2a;

import com.bkanent.common.rag.*;
import com.bkanent.common.authz.*;
import io.a2a.http.*;
import io.a2a.util.Utils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One official transport decorator; GET/Card discovery never receives task credentials. */
public final class BeaconA2aHttpClient implements A2AHttpClient {
    public static final String BEACON_HEADER = "X-A2A-Beacon";
    public static final String BINDING_HEADER = "X-A2A-Binding";
    public static final String AUTHORIZATION_HEADER = "X-Bkanent-A2A-Authorization";
    private static final SecureRandom RANDOM = new SecureRandom();
    public record Identity(String actorId, String audience, RagCredentialContext credentials, long expiresAtEpochSecond,
                           AuthorizationContext authorizationContext, AuthzFreshness freshness, String operation,
                           String callerService, String keyId, PrivateKey signingKey) {
        public Identity(String actorId, String audience, RagCredentialContext credentials) {
            this(actorId, audience, credentials, java.time.Instant.now().getEpochSecond() + 300,
                    null, null, null, null, null, null);
        }
        public Identity(String actorId, String audience, RagCredentialContext credentials, long expiresAtEpochSecond) {
            this(actorId, audience, credentials, expiresAtEpochSecond, null, null, null, null, null, null);
        }
        public static Identity versioned(String audience, AuthorizationContext context, AuthzFreshness freshness,
                                         String operation, String callerService, String keyId, PrivateKey signingKey) {
            return new Identity(context.actorId(), audience, null, context.runHardDeadlineEpochSecond(),
                    context, freshness, operation, callerService, keyId, signingKey);
        }
        public boolean versioned() { return authorizationContext != null; }
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
                    if (current.versioned()) {
                        authenticateVersioned(post, body, current);
                        return;
                    }
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
    private static void authenticateVersioned(PostBuilder post, String body, Identity identity) {
        try {
            var node = Utils.OBJECT_MAPPER.readTree(body);
            String method = node.path("method").asText();
            boolean sending = method.equals("message/send") || method.equals("message/stream");
            if (!sending && !java.util.Set.of("tasks/get", "tasks/cancel", "tasks/resubscribe").contains(method))
                throw new RagException(RagErrorCode.FORBIDDEN);
            var params = node.path("params");
            String task = sending ? params.path("message").path("taskId").asText() : params.path("id").asText();
            var id = node.get("id");
            if (id == null || !(id.isTextual() || id.isIntegralNumber()) || task.isBlank())
                throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            AuthorizationContext context = identity.authorizationContext();
            AuthzFreshness freshness = identity.freshness();
            long now = java.time.Instant.now().getEpochSecond();
            long expiresAt = Math.min(now + AuthzDeadlines.REQUEST_SECONDS,
                    Math.min(context.expiresAtEpochSecond(), Math.min(context.runHardDeadlineEpochSecond(),
                            freshness.notAfterEpochSecond())));
            if (expiresAt <= now || context.runId() == null || context.sessionId() == null
                    || !identity.callerService().equals(context.audience())
                    || !context.actorId().equals(identity.actorId())
                    || identity.operation() == null || identity.signingKey() == null || identity.keyId() == null)
                throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
            byte[] nonceBytes = new byte[32];
            RANDOM.nextBytes(nonceBytes);
            String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
            String requestId = id.asText();
            String bodyHash = AuthzCanonicalHash.bodyHash(body.getBytes(StandardCharsets.UTF_8));
            DelegationBinding binding = new DelegationBinding(identity.callerService(), identity.audience(),
                    context.actorId(), java.util.List.of(identity.operation()), context.runId(), task, requestId,
                    bodyHash, context.policyDigest(), nonce, now, expiresAt);
            String requestHash = AuthzCanonicalHash.delegationRequestHash(context, binding);
            CallerProof proof = AuthzSignature.sign(new CallerProof(identity.callerService(), identity.audience(),
                    AuthzRpcOperation.VALIDATE_DELEGATION, requestId, requestHash, nonce, now, expiresAt,
                    AuthzContract.ALGORITHM, identity.keyId(), null), identity.signingKey());
            String encoded = AuthzWireCodec.encodeDelegatedAuthorizationRequest(
                    new DelegatedAuthorizationRequest(context, freshness, binding, proof));
            post.addHeader(AUTHORIZATION_HEADER, encoded);
        } catch (RagException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        } catch (Exception failure) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
    }
    public static String bodyHash(String body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }
    }
}
