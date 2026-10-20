package com.bkanent.memory.runtime;

import com.bkanent.common.memory.*;
import com.bkanent.common.rpc.AuthenticatedPrincipal;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/** Transport-only client. Current actor is resolved on every protected operation, never from an owner id. */
public final class MemoryRpcClient {
    public record Actor(AuthenticatedPrincipal principal, long expiresAtEpochSecond) { }
    private final MemoryV2RpcService rpc;
    private final String caller;
    private final String keyId;
    private final byte[] secret;
    private final Supplier<Actor> currentActor;

    public MemoryRpcClient(MemoryV2RpcService rpc, String caller, String keyId, byte[] secret, Supplier<Actor> currentActor) {
        this.rpc = rpc; this.caller = caller; this.keyId = keyId; this.secret = secret.clone(); this.currentActor = currentActor;
    }

    public MemoryWorkingSetResponse read(MemoryContextKey context) {
        return invoke(MemoryV2Contract.OPERATION_WORKING_SET, e -> new MemoryWorkingSetRequest(e, context), rpc::readWorkingSet);
    }

    public MemoryBoundaryResponse check(MemoryWorkingSetResponse snapshot, java.util.List<MemorySourceDependency> dependencies) {
        return invoke(MemoryV2Contract.OPERATION_BOUNDARY, e -> new MemoryBoundaryRequest(e, snapshot.contextKey(), snapshot.version(), dependencies), rpc::checkBoundary);
    }

    public MemoryWriterResponse writer(MemoryContextKey context, MemoryWriterRequest.Action action, String runId, long fence) {
        String requestId = action == MemoryWriterRequest.Action.ACQUIRE
                ? stableRequestId(List.of("writer-acquire-v1", context, runId)) : UUID.randomUUID().toString();
        return invoke(MemoryV2Contract.OPERATION_WRITER, requestId,
                e -> new MemoryWriterRequest(e, context, action, runId, fence), rpc::manageWriter);
    }

    public MemoryReceivePermitResponse permit(MemoryContextKey context, java.util.List<MemoryMessage> messages, MemoryWriteAuthority writer) {
        var events = messages.stream().map(m -> new MemoryReceivePermitRequest.EventSubmission(m.source().sourceEventId(),
                m.source().version().contentHash(), "conversation-archive")).toList();
        if (writer == null) throw new MemoryException(MemoryErrorCode.VERSION_CONFLICT);
        String requestId = stableRequestId(List.of("receive-permit-v1", context, writer.runId(), writer.fence(), events));
        return invoke(MemoryV2Contract.OPERATION_RECEIVE_PERMIT, requestId,
                e -> new MemoryReceivePermitRequest(e, context, events, java.util.List.of(), writer), rpc::requestReceivePermit);
    }

    public MemoryDeliveryResponse deliver(MemoryContextKey context, MemoryAssociation association, String permit, java.util.List<MemoryMessage> messages) {
        var request = request(MemoryV2Contract.OPERATION_DELIVER_MESSAGES, e -> new MemoryDeliveryRequest(e, context, association, permit, messages));
        long now = Instant.now().getEpochSecond();
        var proof = MemoryRequestSigner.signServiceDelivery(request, keyId, now, now + 60, nonce(), secret);
        return transport(() -> rpc.deliverMessages(request, proof));
    }

    public MemorySummaryResponse summary(MemorySummary summary, MemoryWriteAuthority writer) {
        return invoke(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, e -> new MemorySummaryRequest(e, summary, writer), rpc::submitSummary);
    }

    private <T extends MemoryV2Request, R> R invoke(String operation, Function<MemoryRequestEnvelope,T> factory,
                                                  BiFunction<T,MemoryRequestProof,R> call) {
        return invoke(operation, UUID.randomUUID().toString(), factory, call);
    }

    private <T extends MemoryV2Request, R> R invoke(String operation, String requestId, Function<MemoryRequestEnvelope,T> factory,
                                                  BiFunction<T,MemoryRequestProof,R> call) {
        var actor = currentActor.get();
        long now = Instant.now().getEpochSecond();
        if (actor == null || actor.expiresAtEpochSecond() <= now) throw new MemoryException(MemoryErrorCode.AUTHENTICATION_EXPIRED);
        T request = request(operation, requestId, factory);
        var proof = MemoryRequestSigner.sign(request, actor.principal(), keyId, now, Math.min(now + 60, actor.expiresAtEpochSecond()), nonce(), secret);
        return transport(() -> call.apply(request, proof));
    }

    private <T extends MemoryV2Request> T request(String operation, Function<MemoryRequestEnvelope,T> factory) {
        return request(operation, UUID.randomUUID().toString(), factory);
    }

    private <T extends MemoryV2Request> T request(String operation, String id, Function<MemoryRequestEnvelope,T> factory) {
        var draft = factory.apply(new MemoryRequestEnvelope(operation, caller, MemoryV2Contract.MEMORY_AUDIENCE, id, "0".repeat(64), 2));
        return factory.apply(new MemoryRequestEnvelope(operation, caller, MemoryV2Contract.MEMORY_AUDIENCE, id, MemoryCanonicalHash.requestBodyHash(draft), 2));
    }

    private <R> R transport(Supplier<R> call) {
        try { return call.get(); }
        catch (MemoryException exception) { throw exception; }
        catch (RuntimeException exception) { throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE); }
    }

    private static String nonce() { return UUID.randomUUID().toString().replace("-", ""); }
    private static String stableRequestId(Object identity) {
        String hash = MemoryCanonicalHash.contentHash(identity);
        return UUID.nameUUIDFromBytes(hash.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
