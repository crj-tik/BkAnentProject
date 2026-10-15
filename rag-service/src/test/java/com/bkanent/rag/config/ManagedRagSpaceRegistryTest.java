package com.bkanent.rag.config;

import com.bkanent.common.rag.*;
import com.bkanent.rag.storage.RagSpaceDefinition;
import com.bkanent.rag.rerank.RerankProfile;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import static org.assertj.core.api.Assertions.*;

class ManagedRagSpaceRegistryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC);
    private static final List<RagStage> STAGES = List.of(RagStage.KEYWORD, RagStage.VECTOR, RagStage.EMBEDDING, RagStage.RERANK);
    private static RagSpaceDefinition space(String id, String index, String collection) {
        return new RagSpaceDefinition(id, "memory", "policy-v1", "schema-v1", index,
                new RagSpaceDefinition.VectorSchema("default", collection, "model-v1", 3, "id", "content", "metadata", "embedding",
                        "source_type", "source_id", "COSINE", "IVF_FLAT", "{\"nlist\":1024}"), Map.of(), Set.of(),
                Set.of("USER"), Set.of("ADMIN"), Map.of("memory-service", Set.of(RagOperation.SEARCH)),
                new RagSpaceDefinition.Limits(500, 500, 200, 8192, 100000, 100000, 20000, 5000, 8000),
                new RerankProfile("native", "reranker", "scoring-v1", RerankProfile.Scale.BOUNDED_0_1));
    }
    @Test void physicalIndexAndCollectionConflictsAreRejectedSeparately() {
        var a = space("a", "index_a", "collection_a");
        for (var b : List.of(space("b", "index_a", "collection_b"), space("b", "index_b", "collection_a"))) {
            assertThatThrownBy(() -> new ManagedRagSpaceRegistry(new RagProperties(Map.of("a", a, "b", b)), List::of, CLOCK))
                    .isInstanceOfSatisfying(RagException.class, e -> assertThat(e.code()).isEqualTo(RagErrorCode.INVALID_ARGUMENT));
        }
    }
    @Test void arbitraryAddressAndLogicalKeyMismatchCannotRegister() {
        assertThatThrownBy(() -> new ManagedRagSpaceRegistry(new RagProperties(Map.of("a", space("a", "http://arbitrary", "collection"))), List::of, CLOCK)).isInstanceOf(RagException.class);
        assertThatThrownBy(() -> new ManagedRagSpaceRegistry(new RagProperties(Map.of("a", space("b", "index", "collection"))), List::of, CLOCK)).isInstanceOf(RagException.class);
    }
    @Test void noStartupProbesNoSearchCreationAndMissingProbesNeverReady() {
        AtomicInteger called = new AtomicInteger();
        var registry = new ManagedRagSpaceRegistry(new RagProperties(Map.of("a", space("a", "index", "collection"))),
                () -> { called.incrementAndGet(); return List.of(); }, CLOCK);
        assertThat(called.get()).isZero();
        assertThat(registry.snapshot()).singleElement().satisfies(s -> assertThat(s.ready()).isFalse());
        assertThat(called.get()).isZero();
        assertThatThrownBy(() -> registry.require("unknown")).isInstanceOf(RagException.class);
        assertThatThrownBy(() -> registry.requireReady("a")).isInstanceOfSatisfying(RagException.class,
                e -> assertThat(e.code()).isEqualTo(RagErrorCode.SPACE_NOT_READY));
        // The failed renewal re-inspected all four stages and published the incomplete evidence.
        assertThat(called.get()).isEqualTo(4);
        assertThat(registry.inspect("req", "a").ready()).isFalse();
        assertThat(called.get()).isEqualTo(8);
        // Missing probes keep the space incomplete, so every read renews once and still fails closed.
        assertThatThrownBy(() -> registry.requireReady("a")).isInstanceOfSatisfying(RagException.class,
                e -> assertThat(e.code()).isEqualTo(RagErrorCode.SPACE_NOT_READY));
        assertThat(called.get()).isEqualTo(12);
    }
    @Test void explicitDiagnosticsInspectEachActualStageAndExpire() {
        AtomicInteger called = new AtomicInteger();
        var registry = new ManagedRagSpaceRegistry(new RagProperties(Map.of("a", space("a", "index", "collection"))),
                () -> probes(called, null), CLOCK);
        assertThat(registry.inspect("req", "a").ready()).isTrue();
        assertThat(called.get()).isEqualTo(4);
        assertThat(registry.requireReady("a").spaceId()).isEqualTo("a");
        assertThat(called.get()).isEqualTo(4);
        var expired = new ManagedRagSpaceRegistry(new RagProperties(Map.of("a", space("a", "index", "collection"))),
                () -> probes(called, null), Clock.offset(CLOCK, Duration.ofSeconds(61)));
        // Stale evidence renews through one real bounded re-inspection, not a permanent ready flag.
        assertThat(expired.requireReady("a").spaceId()).isEqualTo("a");
        assertThat(called.get()).isEqualTo(8);
    }
    @Test void staleEvidenceRenewsOnceSingleFlightAndFailsClosed() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-12T00:00:00Z"));
        AtomicInteger calls = new AtomicInteger();
        var pause = new java.util.concurrent.CountDownLatch(1);
        var failing = new java.util.concurrent.atomic.AtomicBoolean();
        List<RagCapabilityProbe> probes = STAGES.stream().map(stage -> (RagCapabilityProbe) new RagCapabilityProbe() {
            public RagStage stage() { return stage; }
            public void inspect(RagSpaceDefinition space) {
                calls.incrementAndGet();
                if (failing.get()) throw new RagException(RagErrorCode.TIMEOUT);
                if (stage == RagStage.KEYWORD && clock.holdRenewal()) uninterruptibly(pause);
            }
        }).toList();
        clock.setHoldRenewal(false);
        var registry = new ManagedRagSpaceRegistry(new RagProperties(Map.of("a", budget("a", 20000, 5000))), () -> probes, clock);
        try {
            assertThat(registry.inspect("req", "a").ready()).isTrue();
            assertThat(calls.get()).isEqualTo(4);
            clock.advance(Duration.ofSeconds(61));
            clock.setHoldRenewal(true);
            var firstDone = new java.util.concurrent.CountDownLatch(1);
            var first = new AtomicReference<String>();
            var reader = new Thread(() -> { try { first.set(registry.requireReady("a").spaceId()); } finally { firstDone.countDown(); } });
            reader.setDaemon(true); reader.start();
            while (calls.get() < 5) Thread.onSpinWait();
            var secondDone = new java.util.concurrent.CountDownLatch(1);
            var second = new AtomicReference<String>();
            var waiter = new Thread(() -> { try { second.set(registry.requireReady("a").spaceId()); } catch (RagException ignored) { } finally { secondDone.countDown(); } });
            waiter.setDaemon(true); waiter.start();
            // The second caller bounded-waits for the in-flight renewal instead of racing it.
            assertThat(secondDone.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)).isFalse();
            pause.countDown();
            assertThat(firstDone.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(secondDone.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(first.get()).isEqualTo("a");
            assertThat(second.get()).isEqualTo("a");
            assertThat(calls.get()).isEqualTo(8);
            // A renewal whose stages fail publishes its failures; readers fail closed without new probes.
            clock.advance(Duration.ofSeconds(61));
            failing.set(true);
            assertThatThrownBy(() -> registry.requireReady("a"))
                    .isInstanceOfSatisfying(RagException.class, e -> assertThat(e.code()).isEqualTo(RagErrorCode.TIMEOUT));
            assertThat(calls.get()).isEqualTo(12);
            assertThatThrownBy(() -> registry.requireReady("a"))
                    .isInstanceOfSatisfying(RagException.class, e -> assertThat(e.code()).isEqualTo(RagErrorCode.TIMEOUT));
            assertThat(calls.get()).isEqualTo(12);
        } finally { pause.countDown(); registry.close(); }
    }
    private static final class MutableClock extends Clock {
        private volatile Instant now;
        private volatile boolean holdRenewal;
        MutableClock(Instant start) { now = start; }
        void advance(Duration duration) { now = now.plus(duration); }
        void setHoldRenewal(boolean value) { holdRenewal = value; }
        boolean holdRenewal() { return holdRenewal; }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
    @Test void selectedModeCanExplicitlyOmitFailedRerankButDefaultCapabilityRemainsUnavailable() {
        var registry = new ManagedRagSpaceRegistry(new RagProperties(Map.of("a", space("a", "index", "collection"))),
                () -> probes(new AtomicInteger(), RagStage.RERANK), CLOCK);
        var result = registry.inspect("req", "a");
        assertThat(result.ready()).isFalse();
        assertThat(result.failedStages()).containsExactly(new RagStageFailure(RagStage.RERANK, RagErrorCode.SCORING_PROFILE_MISMATCH));
        assertThat(registry.requireReady("a", Set.of(RagStage.KEYWORD)).spaceId()).isEqualTo("a");
        assertThatThrownBy(() -> registry.requireReady("a", Set.of(RagStage.KEYWORD, RagStage.RERANK))).isInstanceOf(RagException.class);
    }
    @Test void explicitDegradationRequiresActualEvidenceAndCannotIgnoreEndpointPolicy() {
        var registry = new ManagedRagSpaceRegistry(new RagProperties(Map.of("a", space("a", "index", "collection"))),
                () -> probes(new AtomicInteger(), RagStage.RERANK), CLOCK);
        Set<RagStage> selected = Set.of(RagStage.KEYWORD, RagStage.VECTOR, RagStage.EMBEDDING, RagStage.RERANK);
        // With bounded renewal the first read re-inspects and legitimately serves degraded readiness.
        assertThat(registry.requireReady("a", selected, RagFailurePolicy.ALLOW_DEGRADED).spaceId()).isEqualTo("a");
        registry.inspect("req", "a");
        assertThat(registry.requireReady("a", selected, RagFailurePolicy.ALLOW_DEGRADED).spaceId()).isEqualTo("a");
        assertThatThrownBy(() -> registry.requireReady("a", selected, RagFailurePolicy.STRICT)).isInstanceOf(RagException.class);
        List<RagCapabilityProbe> unsafe = new ArrayList<>(probes(new AtomicInteger(), null));
        unsafe.removeIf(p -> p.stage() == RagStage.KEYWORD);
        unsafe.add(new RagCapabilityProbe() {
            public RagStage stage() { return RagStage.KEYWORD; }
            public void inspect(RagSpaceDefinition space) { throw new RagException(RagErrorCode.ENDPOINT_POLICY_DENIED); }
        });
        var guarded = new ManagedRagSpaceRegistry(new RagProperties(Map.of("a", space("a", "index", "collection"))), () -> unsafe, CLOCK);
        guarded.inspect("req", "a");
        assertThatThrownBy(() -> guarded.requireReady("a", selected, RagFailurePolicy.ALLOW_DEGRADED))
                .isInstanceOfSatisfying(RagException.class, e -> assertThat(e.code()).isEqualTo(RagErrorCode.ENDPOINT_POLICY_DENIED));
        var missing = new ManagedRagSpaceRegistry(new RagProperties(Map.of("a", space("a", "index", "collection"))), List::of, CLOCK);
        missing.inspect("req", "a");
        assertThatThrownBy(() -> missing.requireReady("a", selected, RagFailurePolicy.ALLOW_DEGRADED)).isInstanceOf(RagException.class);
    }
    @Test void actualProbeAuthorizationFailureIsHardEvenWhenStageIsNotSelected() {
        for (RuntimeException failure : List.of(
                new IllegalStateException(io.grpc.Status.PERMISSION_DENIED.asRuntimeException()),
                new IllegalStateException(io.grpc.Status.UNAUTHENTICATED.asException()),
                new org.springframework.ai.retry.NonTransientAiException("HTTP 403 - sensitive"),
                new org.springframework.web.client.RestClientResponseException("sensitive", 401, "denied", null, null, null))) {
            List<RagCapabilityProbe> actual = new ArrayList<>(probes(new AtomicInteger(), null));
            actual.removeIf(p -> p.stage() == RagStage.VECTOR || p.stage() == RagStage.KEYWORD);
            actual.add(new RagCapabilityProbe() {
                public RagStage stage() { return RagStage.KEYWORD; }
                public void inspect(RagSpaceDefinition space) { }
                public void provision(RagSpaceDefinition space) { }
            });
            actual.add(new RagCapabilityProbe() {
                public RagStage stage() { return RagStage.VECTOR; }
                public void inspect(RagSpaceDefinition space) { throw failure; }
                public void provision(RagSpaceDefinition space) { throw failure; }
            });
            var registry = new ManagedRagSpaceRegistry(new RagProperties(Map.of("a", space("a", "index", "collection"))), () -> actual, CLOCK);
            RagErrorCode expected = RagRuntimeConfiguration.capabilityFailure(failure);
            assertThat(registry.inspect("req", "a").failedStages()).containsExactly(new RagStageFailure(RagStage.VECTOR, expected));
            assertThatThrownBy(() -> registry.requireReady("a", Set.of(RagStage.KEYWORD), RagFailurePolicy.ALLOW_DEGRADED))
                    .isInstanceOfSatisfying(RagException.class, e -> assertThat(e.code()).isEqualTo(expected));
            assertThatThrownBy(() -> registry.provision(new RagProvisionRequest(1, "req", "a", "schema-v1", 3)))
                    .isInstanceOfSatisfying(RagException.class, e -> assertThat(e.code()).isEqualTo(expected));
        }
    }
    @Test void provisionRequiresExactManagedDimensionAndSchemaBeforeCallingBackend() {
        AtomicInteger called = new AtomicInteger();
        var registry = new ManagedRagSpaceRegistry(new RagProperties(Map.of("a", space("a", "index", "collection"))),
                () -> probes(called, null), CLOCK);
        assertThatThrownBy(() -> registry.provision(new RagProvisionRequest(1, "req", "a", "schema-v1", 4))).isInstanceOf(RagException.class);
        assertThat(called.get()).isZero();
    }
    @Test void recordPropertiesPreserveDotSpaceKeysAndNoDefaults() {
        Map<String, Object> values = new HashMap<>();
        String p = "rag.spaces[listing.knowledge].";
        values.put(p + "space-id", "listing.knowledge"); values.put(p + "owner-domain", "listing-master");
        values.put(p + "policy-version", "listing-v1"); values.put(p + "schema-version", "1");
        values.put(p + "elasticsearch-index", "rag_listing");
        values.put(p + "vector-schema.database", "default"); values.put(p + "vector-schema.collection", "rag_listing");
        values.put(p + "search-roles[0]", "ADMIN"); values.put(p + "admin-roles[0]", "ADMIN");
        values.put(p + "allowed-clients[listing-master-service][0]", "SEARCH");
        values.put(p + "fields.listingId.type", "STRING"); values.put(p + "fields.listingId.operators[0]", "IN");
        values.put(p + "fields.listingId.required", "true"); values.put(p + "return-fields[0]", "listingId");
        RagProperties props = new Binder(new MapConfigurationPropertySource(values)).bind("rag", Bindable.of(RagProperties.class)).get();
        assertThat(props.spaces()).containsKey("listing.knowledge");
        assertThat(props.spaces().get("listing.knowledge").allowedClients()).containsKey("listing-master-service");
        assertThat(new RagProperties(null).spaces()).isEmpty();
    }
    private static RagSpaceDefinition budget(String id, long overall, long recall) {
        var s = space(id, "index_" + id, "collection_" + id);
        return new RagSpaceDefinition(s.spaceId(), s.ownerDomain(), s.policyVersion(), s.schemaVersion(), s.elasticsearchIndex(),
                s.vectorSchema(), s.fields(), s.returnFields(), s.searchRoles(), s.adminRoles(), s.allowedClients(),
                new RagSpaceDefinition.Limits(500, 500, 200, 8192, 100000, 100000, overall, recall, recall), s.rerankProfile());
    }
    private static List<RagCapabilityProbe> actions(java.util.function.BiConsumer<RagStage, RagSpaceDefinition> action) {
        return List.of(RagStage.KEYWORD, RagStage.VECTOR, RagStage.EMBEDDING, RagStage.RERANK).stream()
                .map(stage -> (RagCapabilityProbe) new RagCapabilityProbe() {
                    public RagStage stage() { return stage; }
                    public void inspect(RagSpaceDefinition s) { action.accept(stage, s); }
                    public void provision(RagSpaceDefinition s) { action.accept(stage, s); }
                }).toList();
    }
    private static void uninterruptibly(java.util.concurrent.CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) { try { latch.await(); break; } catch (InterruptedException failure) { interrupted = true; } }
        if (interrupted) Thread.currentThread().interrupt();
    }
    @Test void fixedStageOrderAndCumulativeDeadlineIncludeEveryStageWithoutRefreshingBudget() {
        var order = new java.util.concurrent.CopyOnWriteArrayList<RagStage>();
        var s = budget("a", 260, 200);
        var registry = new ManagedRagSpaceRegistry(new RagProperties(Map.of("a", s)), () -> actions((stage, space) -> {
            order.add(stage);
            try { Thread.sleep(110); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        }), CLOCK);
        try {
            long start = System.nanoTime();
            var result = registry.inspect("req", "a");
            assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(850);
            assertThat(order).containsExactly(RagStage.KEYWORD, RagStage.VECTOR, RagStage.EMBEDDING);
            assertThat(result.ready()).isFalse();
            assertThat(result.failedStages()).contains(new RagStageFailure(RagStage.RERANK, RagErrorCode.TIMEOUT));
            assertThatThrownBy(() -> registry.requireReady("a", Set.of(RagStage.KEYWORD), RagFailurePolicy.ALLOW_DEGRADED)).isInstanceOf(RagException.class);
        } finally { registry.close(); }
    }
    @Test void blockedStageReturnsKnownTimeoutButLateWorkCannotPublishOrPermitConcurrentProvision() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var exited = new java.util.concurrent.CountDownLatch(1);
        var s = budget("a", 800, 90);
        var registry = new ManagedRagSpaceRegistry(new RagProperties(Map.of("a", s)), () -> actions((stage, space) -> {
            if (stage == RagStage.KEYWORD) { try { uninterruptibly(release); } finally { exited.countDown(); } }
        }), CLOCK);
        try {
            var result = registry.inspect("req", "a");
            assertThat(result.failedStages()).containsExactly(new RagStageFailure(RagStage.KEYWORD, RagErrorCode.TIMEOUT));
            assertThat(registry.requireReady("a", Set.of(RagStage.KEYWORD, RagStage.VECTOR), RagFailurePolicy.ALLOW_DEGRADED)).isEqualTo(s);
            assertThatThrownBy(() -> registry.provision(new RagProvisionRequest(1, "p", "a", "schema-v1", 3)))
                    .isInstanceOfSatisfying(RagException.class, e -> assertThat(e.code()).isEqualTo(RagErrorCode.DEPENDENCY_UNAVAILABLE));
            release.countDown();
            assertThat(exited.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(registry.snapshot()).singleElement().satisfies(state -> {
                assertThat(state.ready()).isFalse(); assertThat(state.failedStages()).isEqualTo(result.failedStages());
            });
        } finally { release.countDown(); registry.close(); }
    }
    @Test void timedOutProvisionKeepsSchemaLeaseAndDoesNotBlockAnotherSpace() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var a = budget("a", 500, 90); var b = budget("b", 500, 90);
        var registry = new ManagedRagSpaceRegistry(new RagProperties(Map.of("a", a, "b", b)), () -> actions((stage, s) -> {
            if (s.spaceId().equals("a")) uninterruptibly(release);
        }), CLOCK);
        try {
            assertThatThrownBy(() -> registry.provision(new RagProvisionRequest(1, "p", "a", "schema-v1", 3)))
                    .isInstanceOfSatisfying(RagException.class, e -> assertThat(e.code()).isEqualTo(RagErrorCode.TIMEOUT));
            assertThatThrownBy(() -> registry.provision(new RagProvisionRequest(1, "p2", "a", "schema-v1", 3))).isInstanceOf(RagException.class);
            assertThat(registry.inspect("other", "b").ready()).isTrue();
            assertThat(registry.snapshot().stream().filter(state -> state.spaceId().equals("a")).findFirst().orElseThrow().ready()).isFalse();
        } finally { release.countDown(); registry.close(); }
    }
    @Test void boundedQueueTimeConsumesStageBudgetAndCapacityRejectionFailsClosed() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        var calls = new AtomicInteger();
        var s = budget("a", 400, 70);
        var registry = new ManagedRagSpaceRegistry(new RagProperties(Map.of("a", s)), () -> actions((stage, space) -> calls.incrementAndGet()), CLOCK, 1);
        var field = ManagedRagSpaceRegistry.class.getDeclaredField("executor"); field.setAccessible(true);
        var executor = (java.util.concurrent.ThreadPoolExecutor) field.get(registry);
        try {
            executor.execute(() -> { started.countDown(); uninterruptibly(release); });
            assertThat(started.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var queued = registry.inspect("queued", "a");
            assertThat(queued.ready()).isFalse(); assertThat(calls.get()).isZero();
            assertThat(executor.getQueue()).isEmpty();
            executor.execute(() -> uninterruptibly(release));
            var rejected = registry.inspect("capacity", "a");
            assertThat(rejected.failedStages()).hasSize(4).allSatisfy(f -> assertThat(f.code()).isEqualTo(RagErrorCode.DEPENDENCY_UNAVAILABLE));
            assertThat(calls.get()).isZero();
            assertThatThrownBy(() -> registry.requireReady("a", Set.of(RagStage.KEYWORD), RagFailurePolicy.ALLOW_DEGRADED)).isInstanceOf(RagException.class);
            registry.close();
            assertThatThrownBy(() -> registry.inspect("closed", "a")).isInstanceOf(RagException.class);
        } finally { release.countDown(); registry.close(); }
    }
    private static List<RagCapabilityProbe> probes(AtomicInteger calls, RagStage failure) {
        return List.of(RagStage.KEYWORD, RagStage.VECTOR, RagStage.EMBEDDING, RagStage.RERANK).stream().map(stage -> (RagCapabilityProbe) new RagCapabilityProbe() {
            public RagStage stage() { return stage; }
            public void inspect(RagSpaceDefinition space) { calls.incrementAndGet(); if (stage == failure) throw new RagException(RagErrorCode.SCORING_PROFILE_MISMATCH); }
        }).toList();
    }
}
