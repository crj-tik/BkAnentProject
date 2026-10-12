package com.bkanent.rag.config;

import com.bkanent.common.rag.*;
import com.bkanent.rag.storage.RagSpaceDefinition;
import com.bkanent.rag.storage.RagSpaceRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** No search-time creation, and no configured ready flag. Diagnostic evidence expires. */
public final class ManagedRagSpaceRegistry implements RagSpaceRegistry {
    private static final Set<RagStage> REQUIRED = Set.of(RagStage.KEYWORD, RagStage.VECTOR,
            RagStage.EMBEDDING, RagStage.RERANK);
    private final Map<String, RagSpaceDefinition> spaces;
    private final Supplier<List<RagCapabilityProbe>> probes;
    private final Clock clock;
    private final Map<String, Evidence> evidence = new ConcurrentHashMap<>();

    public ManagedRagSpaceRegistry(RagProperties properties, Supplier<List<RagCapabilityProbe>> probes, Clock clock) {
        this.probes = probes;
        this.clock = clock;
        Set<String> indices = new HashSet<>();
        Set<String> collections = new HashSet<>();
        properties.spaces().forEach((id, space) -> {
            validate(id, space);
            if (!indices.add(space.elasticsearchIndex().toLowerCase(Locale.ROOT))
                    || !collections.add(space.vectorSchema().database() + "/" + space.vectorSchema().collection())) {
                throw new RagException(RagErrorCode.INVALID_ARGUMENT, "spaces.physicalTargetConflict");
            }
        });
        spaces = properties.spaces();
    }

    @Override public RagSpaceDefinition require(String spaceId) {
        RagSpaceDefinition space = spaceId == null ? null : spaces.get(spaceId);
        if (space == null) throw new RagException(RagErrorCode.NOT_FOUND, "spaceId");
        return space;
    }

    @Override public RagSpaceDefinition requireReady(String spaceId) {
        return requireReady(spaceId, Set.of(RagStage.KEYWORD, RagStage.VECTOR, RagStage.EMBEDDING));
    }

    @Override public RagSpaceDefinition requireReady(String spaceId, Set<RagStage> requiredStages) {
        return requireReady(spaceId, requiredStages, RagFailurePolicy.STRICT);
    }

    @Override public RagSpaceDefinition requireReady(String spaceId, Set<RagStage> requiredStages, RagFailurePolicy failurePolicy) {
        RagSpaceDefinition space = require(spaceId);
        Evidence state = evidence.get(spaceId);
        if (requiredStages == null || requiredStages.isEmpty() || !REQUIRED.containsAll(requiredStages)
                || failurePolicy == null || state == null || !state.complete()
                || !clock.instant().isBefore(state.expiresAt())) {
            throw new RagException(RagErrorCode.SPACE_NOT_READY);
        }
        for (RagStageFailure failure : state.failures()) {
            boolean selected = requiredStages.contains(failure.stage());
            boolean hard = failure.code() == RagErrorCode.ENDPOINT_POLICY_DENIED
                    || failure.code() == RagErrorCode.SPACE_NOT_READY || failure.code() == RagErrorCode.FORBIDDEN
                    || failure.code() == RagErrorCode.AUTHENTICATION_FAILED || failure.code() == RagErrorCode.AUTHENTICATION_EXPIRED
                    || failure.code() == RagErrorCode.INVALID_ARGUMENT;
            if (hard || (selected && (failurePolicy == RagFailurePolicy.STRICT || !degradable(failure.code())))) {
                throw new RagException(failure.code(), "capability");
            }
        }
        // A known transient failure may be retried by the real pipeline under ALLOW_DEGRADED.
        // Never manufacture an available route when all selected recalls have failed inspection.
        if (failurePolicy == RagFailurePolicy.ALLOW_DEGRADED && requiredStages.stream()
                .filter(stage -> stage == RagStage.KEYWORD || stage == RagStage.VECTOR)
                .noneMatch(stage -> state.failures().stream().noneMatch(f -> f.stage() == stage
                        || stage == RagStage.VECTOR && f.stage() == RagStage.EMBEDDING))) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "capability");
        }
        return space;
    }
    private static boolean degradable(RagErrorCode code) {
        return code == RagErrorCode.DEPENDENCY_UNAVAILABLE || code == RagErrorCode.TIMEOUT
                || code == RagErrorCode.RERANK_PROTOCOL_ERROR || code == RagErrorCode.SCORING_PROFILE_MISMATCH;
    }

    /** Administrative call only; a missing or duplicate probe cannot produce green readiness. */
    public synchronized RagSpaceInspection inspect(String requestId, String spaceId) {
        RagSpaceDefinition space = require(spaceId);
        List<RagStageFailure> failures = new ArrayList<>();
        List<RagCapabilityProbe> available = probes.get();
        boolean complete = true;
        for (RagStage stage : REQUIRED) {
            List<RagCapabilityProbe> matching = available.stream().filter(p -> p.stage() == stage).toList();
            if (matching.size() != 1) {
                complete = false;
                failures.add(new RagStageFailure(stage, RagErrorCode.DEPENDENCY_UNAVAILABLE));
                continue;
            }
            try { matching.get(0).inspect(space); }
            catch (RuntimeException failure) { failures.add(new RagStageFailure(stage, RagRuntimeConfiguration.capabilityFailure(failure))); }
        }
        evidence.put(spaceId, new Evidence(List.copyOf(failures), clock.instant().plusSeconds(60), complete));
        return result(requestId, space, failures.isEmpty(), failures);
    }

    public synchronized RagSpaceInspection provision(RagProvisionRequest request) {
        RagSpaceDefinition space = require(request.spaceId());
        if (!space.schemaVersion().equals(request.schemaVersion()) || space.vectorSchema().dimension() != request.dimension()) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "schema");
        }
        evidence.remove(space.spaceId());
        for (RagStage stage : List.of(RagStage.KEYWORD, RagStage.VECTOR)) {
            List<RagCapabilityProbe> matching = probes.get().stream().filter(p -> p.stage() == stage).toList();
            if (matching.size() != 1) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "schema");
            try { matching.get(0).provision(space); }
            catch (RuntimeException failure) { throw new RagException(RagRuntimeConfiguration.capabilityFailure(failure), "schema"); }
        }
        return inspect(request.requestId(), request.spaceId());
    }

    /** Passive diagnostics, safe for actuator: no network probes or schema mutation. */
    public List<RagSpaceInspection> snapshot() {
        return spaces.values().stream().sorted(Comparator.comparing(RagSpaceDefinition::spaceId)).map(space -> {
            Evidence state = evidence.get(space.spaceId());
            boolean fresh = state != null && clock.instant().isBefore(state.expiresAt());
            List<RagStageFailure> failures = fresh ? state.failures() : REQUIRED.stream()
                    .map(stage -> new RagStageFailure(stage, RagErrorCode.SPACE_NOT_READY)).toList();
            return result("diagnostic", space, fresh && failures.isEmpty(), failures);
        }).toList();
    }

    private static RagSpaceInspection result(String requestId, RagSpaceDefinition space, boolean ready, List<RagStageFailure> failures) {
        return new RagSpaceInspection(1, requestId, space.spaceId(), space.ownerDomain(), space.policyVersion(),
                space.schemaVersion(), space.vectorSchema().modelVersion(), space.vectorSchema().dimension(), ready, failures);
    }

    private static void validate(String id, RagSpaceDefinition s) {
        if (s == null || !Objects.equals(id, s.spaceId()) || !logical(id)
                || !logical(s.ownerDomain()) || !logical(s.policyVersion()) || !logical(s.schemaVersion())
                || s.elasticsearchIndex() == null || !s.elasticsearchIndex().matches("[a-z0-9][a-z0-9_-]{0,254}")
                || s.vectorSchema() == null || s.limits() == null) invalid();
        RagSpaceDefinition.VectorSchema v = s.vectorSchema();
        if (!physical(v.database()) || !physical(v.collection()) || !logical(v.modelVersion())
                || v.dimension() <= 0 || v.dimension() > 65536 || !physical(v.idField())
                || !physical(v.contentField()) || !physical(v.metadataField()) || !physical(v.vectorField())
                || !physical(v.sourceTypeField()) || !physical(v.sourceIdField())
                || v.metricType() == null || v.indexType() == null || v.indexParameters() == null) invalid();
        if (new HashSet<>(List.of(v.idField(), v.contentField(), v.metadataField(), v.vectorField())).size() != 4) invalid();
        RagSpaceDefinition.Limits l = s.limits();
        if (l.maxRecallCandidates() < 1 || l.maxRecallCandidates() > 500 || l.maxRerankCandidates() < 1
                || l.maxRerankCandidates() > 500 || l.maxCandidateLimit() < 1 || l.maxCandidateLimit() > 200
                || l.maxDocumentUtf8Bytes() < 1 || l.maxRecallUtf8Bytes() < 1 || l.maxRerankUtf8Bytes() < 1
                || l.overallTimeoutMillis() < 1 || l.overallTimeoutMillis() > 60000
                || l.recallTimeoutMillis() < 1 || l.rerankTimeoutMillis() < 1
                || l.recallTimeoutMillis() > l.overallTimeoutMillis() || l.rerankTimeoutMillis() > l.overallTimeoutMillis()) invalid();
        s.allowedClients().forEach((caller, ops) -> { if (!logical(caller) || ops == null || ops.isEmpty()) invalid(); });
        s.fields().forEach((field, definition) -> {
            if (!physical(field) || definition.type() == null || definition.operators() == null) invalid();
        });
        if (!s.fields().keySet().containsAll(s.returnFields())) invalid();
        // Missing roles/profile keeps capabilities closed, rather than silently installing ADMIN or public access.
    }
    private static boolean logical(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"); }
    private static boolean physical(String value) { return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]{0,254}"); }
    private static void invalid() { throw new RagException(RagErrorCode.INVALID_ARGUMENT, "spaces"); }
    private record Evidence(List<RagStageFailure> failures, Instant expiresAt, boolean complete) { }
}
