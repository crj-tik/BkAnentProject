package com.bkanent.common.a2a;

import com.bkanent.common.rag.*;
import com.bkanent.common.authz.DelegatedAuthorizationRequest;
import io.a2a.util.Utils;
import org.springframework.web.servlet.function.ServerRequest;
import reactor.core.publisher.Flux;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Runs before official handler touches TaskStore/queue, and before every outgoing SSE item. */
public final class A2aAuthenticatedRequestGuard {
    private static final Logger log = LoggerFactory.getLogger(A2aAuthenticatedRequestGuard.class);
    private final A2aBeaconAuthentication auth;
    private final A2aAuthorizationVerifier authorizationVerifier;
    private final Map<String, Owner> owners = new HashMap<>();
    public A2aAuthenticatedRequestGuard(A2aBeaconAuthentication auth) { this(auth, null); }
    public A2aAuthenticatedRequestGuard(A2aBeaconAuthentication auth, A2aAuthorizationVerifier authorizationVerifier) {
        this.auth = auth;
        this.authorizationVerifier = authorizationVerifier;
    }
    public Object handle(String body, ServerRequest.Headers headers, Supplier<Object> official) {
        if (authorizationVerifier != null && authorizationVerifier.enabled())
            return handleVersioned(body, headers, official);
        if (!headerValues(headers, BeaconA2aHttpClient.AUTHORIZATION_HEADER).isEmpty())
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        return handleBeacon(body, headers, official);
    }
    private Object handleVersioned(String body, ServerRequest.Headers headers, Supplier<Object> official) {
        String stage = "versioned-headers";
        try {
            var authzHeaders = headerValues(headers, BeaconA2aHttpClient.AUTHORIZATION_HEADER);
            if (authzHeaders.size() != 1
                    || !headerValues(headers, BeaconA2aHttpClient.BEACON_HEADER).isEmpty()
                    || !headerValues(headers, BeaconA2aHttpClient.BINDING_HEADER).isEmpty())
                throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            stage = "versioned-proof";
            A2aAuthorizationVerifier.VerifiedRequest verified = authorizationVerifier.verify(authzHeaders.get(0), body);
            var facts = verified.facts();
            DelegatedAuthorizationRequest envelope = verified.envelope();
            A2aCredentialContext.Lease lease = A2aCredentialContext.Lease.versioned(envelope, facts.method());
            stage = "task-owner";
            bindOwner(facts.taskId(), facts.contextId(), lease.binding, facts.sending());
            try (var ignored = A2aCredentialContext.open(lease)) {
                if (facts.sending()) A2aCredentialContext.reserveTask(facts.taskId());
                try {
                    stage = "official-handler";
                    Object result = official.get();
                    if (result instanceof Flux<?> flux) return flux.doOnNext(value -> lease.verify());
                    lease.verify();
                    return result;
                } finally {
                    if (facts.sending()) A2aCredentialContext.releaseUndispatchedTask(facts.taskId(), lease);
                }
            }
        } catch (RagException ex) {
            log.warn("A2A authentication guard rejected stage={} code={}", stage, ex.code());
            throw ex;
        } catch (Exception ex) {
            log.warn("A2A authentication guard rejected stage={} code={}", stage, RagErrorCode.AUTHENTICATION_FAILED);
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
    }
    private static java.util.List<String> headerValues(ServerRequest.Headers headers, String name) {
        if (headers == null) return java.util.List.of();
        java.util.List<String> values = headers.header(name);
        return values == null ? java.util.List.of() : values;
    }
    private Object handleBeacon(String body, ServerRequest.Headers headers, Supplier<Object> official) {
        String stage = "headers";
        try {
            String reference = headers.firstHeader(BeaconA2aHttpClient.BEACON_HEADER);
            String encoded = headers.firstHeader(BeaconA2aHttpClient.BINDING_HEADER);
            if (reference == null || encoded == null || encoded.length() > 8192
                    || headers.header(BeaconA2aHttpClient.BEACON_HEADER).size() != 1
                    || headers.header(BeaconA2aHttpClient.BINDING_HEADER).size() != 1)
                throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            stage = "binding";
            A2aBeaconBinding binding = Utils.OBJECT_MAPPER.readValue(Base64.getUrlDecoder().decode(encoded), A2aBeaconBinding.class);
            stage = "request-body";
            var node = Utils.OBJECT_MAPPER.readTree(body);
            String method = node.path("method").asText();
            boolean sending = method.equals("message/send") || method.equals("message/stream");
            if (!sending && !Set.of("tasks/get", "tasks/cancel", "tasks/resubscribe").contains(method))
                throw new RagException(RagErrorCode.FORBIDDEN);
            String task = sending ? node.path("params").path("message").path("taskId").asText() : node.path("params").path("id").asText();
            String context = sending ? node.path("params").path("message").path("contextId").asText() : null;
            if (sending && !node.path("params").path("configuration").path("pushNotification").isMissingNode()
                    && !node.path("params").path("configuration").path("pushNotification").isNull())
                throw new RagException(RagErrorCode.FORBIDDEN);
            if (!auth.service().equals(binding.audience()) || !method.equals(binding.method()) || !task.equals(binding.taskId())
                    || !node.path("id").asText().equals(binding.requestId()) || !BeaconA2aHttpClient.bodyHash(body).equals(binding.bodyHash()))
                throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            stage = "auth-resolve";
            RagCredentialContext credentials = RagCredentialContext.beacon(reference);
            AuthRagPrincipal principal = auth.resolve(binding, credentials);
            stage = "task-owner";
            bindOwner(task, context, binding, sending);
            A2aCredentialContext.Lease lease = new A2aCredentialContext.Lease(binding, credentials,
                    principal.expiresAtEpochSecond(), () -> auth.resolve(binding, credentials));
            try (var ignored = A2aCredentialContext.open(lease)) {
                if (sending) A2aCredentialContext.reserveTask(task);
                try {
                    stage = "official-handler";
                    Object result = official.get();
                    if (result instanceof Flux<?> flux) return flux.doOnNext(value -> lease.verify());
                    lease.verify(); return result;
                } finally {
                    // Once accepted by the SDK executor, only its original command may release
                    // the reservation. A failed/unsupported forward with no dispatch can retry.
                    if (sending) A2aCredentialContext.releaseUndispatchedTask(task, lease);
                }
            }
        } catch (RagException ex) {
            log.warn("A2A authentication guard rejected stage={} code={}", stage, ex.code());
            throw ex;
        } catch (Exception ex) {
            log.warn("A2A authentication guard rejected stage={} code={}", stage, RagErrorCode.AUTHENTICATION_FAILED);
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
    }
    private synchronized void bindOwner(String task, String context, A2aBeaconBinding binding, boolean sending) {
        Owner owner = owners.get(task);
        if (owner == null) {
            if (!sending) throw new RagException(RagErrorCode.FORBIDDEN);
            if (owners.size() >= 10000) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
            owners.put(task, new Owner(binding.actorId(), binding.callerService(), context));
        } else if (!owner.actor.equals(binding.actorId()) || !owner.caller.equals(binding.callerService())
                || sending && !java.util.Objects.equals(context, owner.context)) throw new RagException(RagErrorCode.FORBIDDEN);
    }
    private record Owner(String actor, String caller, String context) { }
}
