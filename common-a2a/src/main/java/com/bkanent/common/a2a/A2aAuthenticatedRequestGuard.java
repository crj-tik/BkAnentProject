package com.bkanent.common.a2a;

import com.bkanent.common.rag.*;
import io.a2a.util.Utils;
import org.springframework.web.servlet.function.ServerRequest;
import reactor.core.publisher.Flux;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Runs before official handler touches TaskStore/queue, and before every outgoing SSE item. */
public final class A2aAuthenticatedRequestGuard {
    private final A2aBeaconAuthentication auth;
    private final Map<String, Owner> owners = new HashMap<>();
    public A2aAuthenticatedRequestGuard(A2aBeaconAuthentication auth) { this.auth = auth; }
    public Object handle(String body, ServerRequest.Headers headers, Supplier<Object> official) {
        try {
            String reference = headers.firstHeader(BeaconA2aHttpClient.BEACON_HEADER);
            String encoded = headers.firstHeader(BeaconA2aHttpClient.BINDING_HEADER);
            if (reference == null || encoded == null || encoded.length() > 8192
                    || headers.header(BeaconA2aHttpClient.BEACON_HEADER).size() != 1
                    || headers.header(BeaconA2aHttpClient.BINDING_HEADER).size() != 1)
                throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            A2aBeaconBinding binding = Utils.OBJECT_MAPPER.readValue(Base64.getUrlDecoder().decode(encoded), A2aBeaconBinding.class);
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
            RagCredentialContext credentials = RagCredentialContext.beacon(reference);
            AuthRagPrincipal principal = auth.resolve(binding, credentials);
            bindOwner(task, context, binding, sending);
            A2aCredentialContext.Lease lease = new A2aCredentialContext.Lease(binding, credentials,
                    principal.expiresAtEpochSecond(), () -> auth.resolve(binding, credentials));
            try (var ignored = A2aCredentialContext.open(lease)) {
                if (sending) A2aCredentialContext.reserveTask(task);
                try {
                    Object result = official.get();
                    if (result instanceof Flux<?> flux) return flux.doOnNext(value -> lease.verify());
                    lease.verify(); return result;
                } finally {
                    // Once accepted by the SDK executor, only its original command may release
                    // the reservation. A failed/unsupported forward with no dispatch can retry.
                    if (sending) A2aCredentialContext.releaseUndispatchedTask(task, lease);
                }
            }
        } catch (RagException ex) { throw ex; }
        catch (Exception ex) { throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }
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
