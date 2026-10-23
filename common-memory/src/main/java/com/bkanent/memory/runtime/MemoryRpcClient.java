package com.bkanent.memory.runtime;

import com.bkanent.common.memory.*;
import com.bkanent.common.rpc.AuthenticatedPrincipal;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
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
    private final PrivateKey signingKey;
    private final Supplier<Actor> currentActor;

    public MemoryRpcClient(MemoryV2RpcService rpc, String caller, String keyId, PrivateKey signingKey, Supplier<Actor> currentActor) {
        this.rpc = rpc; this.caller = caller; this.keyId = keyId; this.signingKey = java.util.Objects.requireNonNull(signingKey);
        this.currentActor = currentActor;
    }

    public MemoryWorkingSetResponse read(MemoryContextKey context) {
        return invoke(MemoryV2Contract.OPERATION_WORKING_SET, e -> new MemoryWorkingSetRequest(e, context), rpc::readWorkingSet);
    }

    public MemoryMatterWriteResponse transitionMatter(MemoryContextKey context, MemoryMatterIntent intent, MemorySourceDependency evidence,
            long expectedConversationRevision, MemoryWriteAuthority writer) {
        return invoke(MemoryV2Contract.OPERATION_MATTER_WRITE, e -> new MemoryMatterWriteRequest(e,context,intent,evidence,expectedConversationRevision,writer),rpc::transitionMatter);
    }

    public MemoryEntityQueryResponse entities(MemoryContextKey context, String cursor, int limit) {
        return invoke(MemoryV2Contract.OPERATION_QUERY_ENTITIES,e -> new MemoryEntityQueryRequest(e,context,cursor,limit),rpc::queryEntities);
    }

    public MemoryBoundaryResponse check(MemoryWorkingSetResponse snapshot, java.util.List<MemorySourceDependency> dependencies) {
        return check(snapshot, dependencies, List.of());
    }

    public MemoryBoundaryResponse check(MemoryWorkingSetResponse snapshot, java.util.List<MemorySourceDependency> dependencies,
                                        java.util.List<MemoryPreferenceSelectionRef> preferenceSelections) {
        return check(snapshot, dependencies, preferenceSelections, List.of());
    }

    public MemoryBoundaryResponse check(MemoryWorkingSetResponse snapshot, java.util.List<MemorySourceDependency> dependencies,
                                        java.util.List<MemoryPreferenceSelectionRef> preferenceSelections,
                                        java.util.List<MemoryExperienceSelectionRef> experienceSelections) {
        return invoke(MemoryV2Contract.OPERATION_BOUNDARY, e -> new MemoryBoundaryRequest(e, snapshot.contextKey(), snapshot.version(),
                dependencies, preferenceSelections, experienceSelections), rpc::checkBoundary);
    }

    public MemoryPreferenceWriteResponse submitPreference(MemoryContextKey context, long expectedRevision, MemoryPreferenceAssessment candidate) {
        return invoke(MemoryV2Contract.OPERATION_PREFERENCE_WRITE, e -> new MemoryPreferenceWriteRequest(e, context, expectedRevision, candidate), rpc::submitPreferenceAssessment);
    }

    public MemoryPreferenceDetailResponse preferenceHistory(MemoryContextKey context, String blockId, PreferenceHistoryCursor cursor, int limit, Instant asOf, String matterId) {
        return invoke(MemoryV2Contract.OPERATION_PREFERENCE_DETAIL, e -> new MemoryPreferenceDetailRequest(e, context, blockId, cursor, limit, asOf, matterId), rpc::readPreferenceHistory);
    }

    public MemoryPreferenceAssemblyResponse assemblePreferences(MemoryContextKey context, String matterId, int tokenBudget) {
        return invoke(MemoryV2Contract.OPERATION_ASSEMBLE_PREFERENCES,
                e -> new MemoryPreferenceAssemblyRequest(e, context, matterId, tokenBudget), rpc::assemblePreferences);
    }

    public MemoryExperienceQueryResponse searchExperiences(MemoryContextKey context, String matterId, int limit, int tokenBudget) {
        return invoke(MemoryV2Contract.OPERATION_SEARCH_EXPERIENCES,
                e -> new MemoryExperienceQueryRequest(e, context, null, null, limit, null, matterId, tokenBudget), rpc::searchExperiences);
    }

    public MemoryShareGrantManagementResponse createShareGrant(MemoryContextKey context, MemoryShareGrant grant) {
        return invoke(MemoryV2Contract.OPERATION_SHARE_GRANT_MANAGE,
                e -> new MemoryShareGrantManagementRequest(e, context, MemoryShareGrantManagementRequest.Action.CREATE,
                        grant.grant().grantId(), 0, grant), rpc::manageShareGrant);
    }

    public MemorySharedExportStatusResponse submitSharedExport(MemoryContextKey context, String exportId,
            String sourceInvocationId, MemoryAssociation association, MemoryGrantRef grant, String purpose,
            String brief, MemoryJsonValue payload, List<MemorySourceDependency> dependencies) {
        return invoke(MemoryV2Contract.OPERATION_SHARED_EXPORT_SUBMIT,
                e -> new MemorySharedExportSubmitRequest(e, context, exportId, 0, sourceInvocationId,
                        association, grant, purpose, brief, payload, dependencies), rpc::submitSharedExport);
    }

    public MemorySharedExportStatusResponse commitSharedExport(MemoryContextKey context,
            MemorySharedExportStatusResponse staged) {
        if (staged == null || staged.version() == null) throw new MemoryException(MemoryErrorCode.VERSION_CONFLICT);
        if (staged.state() != MemorySharedExport.State.PENDING) return staged;
        return invoke(MemoryV2Contract.OPERATION_SHARED_EXPORT_COMMIT,
                e -> new MemorySharedExportCommitRequest(e, context, staged.sourceExportId(),
                        staged.version().revision(), staged.version().contentHash()), rpc::commitSharedExport);
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
        var proof = MemoryRequestSigner.signServiceDelivery(request, keyId, now, now + 60, nonce(), signingKey);
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
        var proof = MemoryRequestSigner.sign(request, actor.principal(), keyId, now,
                Math.min(now + 60, actor.expiresAtEpochSecond()), nonce(), signingKey);
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
