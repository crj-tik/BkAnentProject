package com.bkanent.rag.config;

import com.bkanent.common.rag.*;
import com.bkanent.rag.storage.RagSpaceDefinition;
import com.bkanent.rag.storage.RagSpaceRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import com.bkanent.rag.security.http.RagHttpDeadline;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** No search-time creation and no configured ready flag: expired evidence renews through a bounded single-flight re-inspection that still fails closed. */
public final class ManagedRagSpaceRegistry implements RagSpaceRegistry {
    private static final List<RagStage> STAGES = List.of(RagStage.KEYWORD, RagStage.VECTOR,
            RagStage.EMBEDDING, RagStage.RERANK);
    private static final Set<RagStage> REQUIRED = Set.copyOf(STAGES);
    private final Map<String, RagSpaceDefinition> spaces;
    private final Supplier<List<RagCapabilityProbe>> probes;
    private final Clock clock;
    private final Map<String, Evidence> evidence = new ConcurrentHashMap<>();
    private final Map<String, Operation> active = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;
    private volatile boolean closed;

    public ManagedRagSpaceRegistry(RagProperties properties, Supplier<List<RagCapabilityProbe>> probes, Clock clock) {
        this(properties, probes, clock, 4);
    }

    ManagedRagSpaceRegistry(RagProperties properties, Supplier<List<RagCapabilityProbe>> probes, Clock clock, int concurrency) {
        if (concurrency < 1 || concurrency > 64) invalid();
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
        executor = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(concurrency), task -> {
                    Thread thread = new Thread(task, "rag-inspect"); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
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
        if (requiredStages == null || requiredStages.isEmpty() || !REQUIRED.containsAll(requiredStages)
                || failurePolicy == null) {
            throw new RagException(RagErrorCode.SPACE_NOT_READY);
        }
        Evidence state = evidence.get(spaceId);
        if (stale(state)) {
            renew(space);
            state = evidence.get(spaceId);
        }
        if (stale(state)) throw new RagException(RagErrorCode.SPACE_NOT_READY);
        Evidence evidenceState = state;
        for (RagStageFailure failure : evidenceState.failures()) {
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
                .noneMatch(stage -> evidenceState.failures().stream().noneMatch(f -> f.stage() == stage
                        || stage == RagStage.VECTOR && f.stage() == RagStage.EMBEDDING))) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "capability");
        }
        return space;
    }
    private static boolean degradable(RagErrorCode code) {
        return code == RagErrorCode.DEPENDENCY_UNAVAILABLE || code == RagErrorCode.TIMEOUT
                || code == RagErrorCode.RERANK_PROTOCOL_ERROR || code == RagErrorCode.SCORING_PROFILE_MISMATCH;
    }

    private boolean stale(Evidence state) {
        return state == null || !state.complete() || !clock.instant().isBefore(state.expiresAt());
    }

    /**
     * Bounded single-flight renewal of stale capability evidence: exactly one real re-inspection per
     * space; concurrent callers bounded-wait for the in-flight operation instead of racing it. The
     * renewal only inspects (never provisions), keeps the coordinator-only publication rule, and a
     * failed renewal publishes its failures so every reader still fails closed.
     */
    private void renew(RagSpaceDefinition space) {
        Operation operation = new Operation(space.spaceId());
        Operation inFlight = active.putIfAbsent(space.spaceId(), operation);
        if (inFlight != null) {
            try { inFlight.awaitDone(space.limits().overallTimeoutMillis(), TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return;
        }
        try {
            if (closed) return;
            long deadline = RagHttpDeadline.capture(Duration.ofMillis(space.limits().overallTimeoutMillis()));
            inspect("capabilityRenewal", space, deadline, operation);
        } finally { operation.release(); }
    }

    /** Administrative call only. Queue wait and lazy backend construction consume the same deadline. */
    public RagSpaceInspection inspect(String requestId, String spaceId) {
        RagSpaceDefinition space = require(spaceId);
        long deadline = RagHttpDeadline.capture(Duration.ofMillis(space.limits().overallTimeoutMillis()));
        Operation operation = begin(spaceId);
        try { return inspect(requestId, space, deadline, operation); }
        finally { operation.release(); }
    }

    private RagSpaceInspection inspect(String requestId, RagSpaceDefinition space, long deadline, Operation operation) {
        List<RagStageFailure> failures = new ArrayList<>();
        boolean complete = true;
        for (RagStage stage : STAGES) {
            if (System.nanoTime() >= deadline) {
                complete = false;
                failures.add(new RagStageFailure(stage, RagErrorCode.TIMEOUT));
                continue;
            }
            try { execute(space, stage, deadline, operation, false); }
            catch (RagException failure) {
                if ("capabilityUnexecuted".equals(failure.field()) || "capabilityMissing".equals(failure.field())
                        || "capabilityCapacity".equals(failure.field())) complete = false;
                failures.add(new RagStageFailure(stage, failure.code()));
            }
        }
        // Workers never publish evidence. A cancelled/late stage cannot turn this result green.
        if (!closed) evidence.put(space.spaceId(), new Evidence(List.copyOf(failures), clock.instant().plusSeconds(60), complete));
        return result(requestId, space, !closed && complete && failures.isEmpty(), failures);
    }

    public RagSpaceInspection provision(RagProvisionRequest request) {
        RagSpaceDefinition space = require(request.spaceId());
        if (!space.schemaVersion().equals(request.schemaVersion()) || space.vectorSchema().dimension() != request.dimension()) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "schema");
        }
        long deadline = RagHttpDeadline.capture(Duration.ofMillis(space.limits().overallTimeoutMillis()));
        Operation operation = begin(space.spaceId());
        try {
            for (RagStage stage : List.of(RagStage.KEYWORD, RagStage.VECTOR)) execute(space, stage, deadline, operation, true);
            return inspect(request.requestId(), space, deadline, operation);
        } finally { operation.release(); }
    }

    private Operation begin(String spaceId) {
        Operation operation = new Operation(spaceId);
        if (closed || active.putIfAbsent(spaceId, operation) != null) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "capabilityBusy");
        }
        evidence.remove(spaceId);
        return operation;
    }

    private void execute(RagSpaceDefinition space, RagStage stage, long overallDeadline, Operation operation, boolean provision) {
        long budgetMillis = stage == RagStage.RERANK ? Math.min(8000, space.limits().rerankTimeoutMillis())
                : Math.min(5000, space.limits().recallTimeoutMillis());
        long deadline = Math.min(overallDeadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis));
        if (System.nanoTime() >= deadline) throw new RagException(RagErrorCode.TIMEOUT, "capabilityUnexecuted");
        operation.retain();
        AtomicBoolean invoked = new AtomicBoolean();
        StageTask task = new StageTask(() -> {
            try (var ignored = RagHttpDeadline.until(deadline)) {
                RagHttpDeadline.remainingNanos(deadline);
                List<RagCapabilityProbe> matching = probes.get().stream().filter(p -> p.stage() == stage).toList();
                if (matching.size() != 1) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "capabilityMissing");
                RagHttpDeadline.remainingNanos(deadline);
                invoked.set(true);
                if (provision) matching.get(0).provision(space); else matching.get(0).inspect(space);
                RagHttpDeadline.remainingNanos(deadline);
                return null;
            }
        }, operation);
        try {
            executor.execute(task);
            task.get(RagHttpDeadline.remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException failure) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "capabilityCapacity");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new RagException(RagErrorCode.TIMEOUT, "capabilityUnexecuted");
        } catch (TimeoutException failure) {
            throw new RagException(RagErrorCode.TIMEOUT, invoked.get() ? "capability" : "capabilityUnexecuted");
        } catch (RagException failure) {
            if (failure.code() == RagErrorCode.TIMEOUT && !invoked.get()) throw new RagException(RagErrorCode.TIMEOUT, "capabilityUnexecuted");
            throw failure;
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RagException rag) {
                if (rag.code() == RagErrorCode.TIMEOUT && !invoked.get()) throw new RagException(RagErrorCode.TIMEOUT, "capabilityUnexecuted");
                throw rag;
            }
            throw new RagException(RagRuntimeConfiguration.capabilityFailure(failure.getCause()), "capability");
        } finally {
            task.cancel(true);
            executor.remove(task);
        }
    }

    // Reference count is released on actual worker exit, NOT Future cancellation completion.
    private final class Operation {
        private final String spaceId;
        private final CountDownLatch done = new CountDownLatch(1);
        private int references = 1;
        Operation(String spaceId) { this.spaceId = spaceId; }
        synchronized void retain() { references++; }
        synchronized void release() { if (--references == 0) { active.remove(spaceId, this); done.countDown(); } }
        void awaitDone(long timeout, TimeUnit unit) throws InterruptedException { done.await(timeout, unit); }
    }
    private static final class StageTask extends FutureTask<Void> {
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final Operation operation;
        StageTask(Callable<Void> action, Operation operation) { super(action); this.operation = operation; }
        @Override public void run() {
            if (!claimed.compareAndSet(false, true)) return;
            try { super.run(); } finally { operation.release(); }
        }
        @Override public boolean cancel(boolean interrupt) {
            boolean cancelled = super.cancel(interrupt);
            if (claimed.compareAndSet(false, true)) operation.release();
            return cancelled;
        }
    }

    @PreDestroy public void close() {
        closed = true;
        evidence.clear();
        for (Runnable queued : executor.shutdownNow()) if (queued instanceof Future<?> future) future.cancel(true);
    }

    /** Passive diagnostics, safe for actuator: no network probes or schema mutation. */
    public List<RagSpaceInspection> snapshot() {
        return spaces.values().stream().sorted(Comparator.comparing(RagSpaceDefinition::spaceId)).map(space -> {
            Evidence state = evidence.get(space.spaceId());
            boolean fresh = state != null && clock.instant().isBefore(state.expiresAt());
            List<RagStageFailure> failures = fresh ? state.failures() : STAGES.stream()
                    .map(stage -> new RagStageFailure(stage, RagErrorCode.SPACE_NOT_READY)).toList();
            return result("diagnostic", space, fresh && state.complete() && failures.isEmpty(), failures);
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
        if (s.candidateAuthorizationRequired()
                && !s.allowedClients().getOrDefault("memory-service", Set.of()).contains(RagOperation.MEMORY_SEARCH)) invalid();
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
