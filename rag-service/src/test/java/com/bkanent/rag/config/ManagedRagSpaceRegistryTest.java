package com.bkanent.rag.config;

import com.bkanent.common.rag.*;
import com.bkanent.rag.storage.RagSpaceDefinition;
import com.bkanent.rag.rerank.RerankProfile;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import static org.assertj.core.api.Assertions.*;

class ManagedRagSpaceRegistryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC);
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
        assertThatThrownBy(() -> registry.requireReady("a")).isInstanceOf(RagException.class);
        assertThat(registry.inspect("req", "a").ready()).isFalse();
        assertThatThrownBy(() -> registry.requireReady("a")).isInstanceOf(RagException.class);
    }
    @Test void explicitDiagnosticsInspectEachActualStageAndExpire() {
        AtomicInteger called = new AtomicInteger();
        var registry = new ManagedRagSpaceRegistry(new RagProperties(Map.of("a", space("a", "index", "collection"))),
                () -> probes(called, null), CLOCK);
        assertThat(registry.inspect("req", "a").ready()).isTrue();
        assertThat(called.get()).isEqualTo(4);
        assertThat(registry.requireReady("a").spaceId()).isEqualTo("a");
        var expired = new ManagedRagSpaceRegistry(new RagProperties(Map.of("a", space("a", "index", "collection"))),
                () -> probes(called, null), Clock.offset(CLOCK, Duration.ofSeconds(61)));
        assertThatThrownBy(() -> expired.requireReady("a")).isInstanceOf(RagException.class);
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
        assertThatThrownBy(() -> registry.requireReady("a", selected, RagFailurePolicy.ALLOW_DEGRADED)).isInstanceOf(RagException.class);
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
    private static List<RagCapabilityProbe> probes(AtomicInteger calls, RagStage failure) {
        return List.of(RagStage.KEYWORD, RagStage.VECTOR, RagStage.EMBEDDING, RagStage.RERANK).stream().map(stage -> (RagCapabilityProbe) new RagCapabilityProbe() {
            public RagStage stage() { return stage; }
            public void inspect(RagSpaceDefinition space) { calls.incrementAndGet(); if (stage == failure) throw new RagException(RagErrorCode.SCORING_PROFILE_MISMATCH); }
        }).toList();
    }
}
