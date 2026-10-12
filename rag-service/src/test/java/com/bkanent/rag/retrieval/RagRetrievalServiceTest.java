package com.bkanent.rag.retrieval;

import com.bkanent.common.rag.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static com.bkanent.rag.retrieval.RetrievalTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RagRetrievalServiceTest {
    private final RagRetrievalService.Recall normal = (s, q, f, n, d) -> List.of(hit("a", 3, RagRecallMode.VECTOR));
    private final RagRetrievalService.Rerank rank = (q, c, p) -> c;
    @Test void strictFailsAndExplicitDegradedPreservesSingleScale() {
        RagRetrievalService.Recall failing = (s, q, f, n, d) -> { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); };
        var service = new RagRetrievalService(registry(), failing, normal, rank, (s, c) -> true, 2);
        try {
            assertThrows(RagException.class, () -> service.search(request(false, RagFailurePolicy.STRICT), List.of()));
            var result = service.search(request(false, RagFailurePolicy.ALLOW_DEGRADED), List.of());
            assertTrue(result.degraded());
            assertEquals(RagSearchMode.VECTOR, result.effectiveMode());
            assertEquals(RagStage.KEYWORD, result.failedStages().get(0).stage());
            assertEquals(RagScoreKind.RANK_SINGLE, result.candidates().get(0).scoreKind());
        } finally { service.close(); }
    }
    @Test void authSecurityAndLedgerFailuresNeverDegrade() {
        for (RagErrorCode code : List.of(RagErrorCode.ENDPOINT_POLICY_DENIED, RagErrorCode.FORBIDDEN,
                RagErrorCode.AUTHENTICATION_FAILED, RagErrorCode.SPACE_NOT_READY)) {
            var service = new RagRetrievalService(registry(), (s, q, f, n, d) -> { throw new RuntimeException(new RagException(code)); }, normal, rank, (s, c) -> true, 2);
            try { assertEquals(code, assertThrows(RagException.class,
                    () -> service.search(request(false, RagFailurePolicy.ALLOW_DEGRADED), List.of())).code()); }
            finally { service.close(); }
        }
        var ledger = new RagRetrievalService(registry(), normal, normal, rank,
                (s, c) -> { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }, 2);
        try { assertThrows(RagException.class, () -> ledger.search(request(false, RagFailurePolicy.ALLOW_DEGRADED), List.of())); }
        finally { ledger.close(); }
    }
    @Test void actualHttpAndGrpcAuthenticationFailuresNeverRetryOrDegrade() {
        List<RuntimeException> denied = List.of(
                new org.springframework.web.client.HttpClientErrorException(org.springframework.http.HttpStatus.UNAUTHORIZED),
                new org.springframework.web.client.HttpClientErrorException(org.springframework.http.HttpStatus.FORBIDDEN),
                io.grpc.Status.UNAUTHENTICATED.asRuntimeException(), io.grpc.Status.PERMISSION_DENIED.asRuntimeException(),
                new io.milvus.exception.ServerException("denied", 3, io.milvus.grpc.ErrorCode.PermissionDenied));
        for (int i = 0; i < denied.size(); i++) {
            RuntimeException error = denied.get(i);
            AtomicInteger calls = new AtomicInteger();
            var service = new RagRetrievalService(registry(), (s, q, f, n, d) -> {
                calls.incrementAndGet(); throw new RuntimeException(error);
            }, normal, rank, (s, c) -> true, 2);
            try {
                assertEquals(i == 0 || i == 2 ? RagErrorCode.AUTHENTICATION_FAILED : RagErrorCode.FORBIDDEN,
                        assertThrows(RagException.class, () -> service.search(request(false, RagFailurePolicy.ALLOW_DEGRADED), List.of())).code());
                assertEquals(1, calls.get());
            } finally { service.close(); }
        }
    }
    @Test void springAiRetryExceptionsWithDroppedHttpCauseNeverRetryOrDegrade() {
        for (int status : new int[] {401, 403}) {
            for (RuntimeException error : List.of(
                    new org.springframework.ai.retry.NonTransientAiException("HTTP " + status + " - provider body"),
                    new org.springframework.ai.retry.TransientAiException("HTTP " + status + " - provider body"))) {
                AtomicInteger calls = new AtomicInteger();
                RagRetrievalService.Recall embedding = (s, q, f, n, d) -> {
                    calls.incrementAndGet(); throw new RuntimeException(error);
                };
                var service = new RagRetrievalService(registry(), normal, embedding, rank, (s, c) -> true, 2);
                try {
                    assertNull(error.getCause());
                    assertEquals(status == 401 ? RagErrorCode.AUTHENTICATION_FAILED : RagErrorCode.FORBIDDEN,
                            assertThrows(RagException.class, () -> service.search(request(false, RagFailurePolicy.ALLOW_DEGRADED), List.of())).code());
                    assertEquals(1, calls.get());
                } finally { service.close(); }
            }
        }
    }
    @Test void springAiStatusRecognitionRequiresTypedExceptionAndCompleteLeadingKey() {
        for (RuntimeException error : List.of(
                new RuntimeException("HTTP 401 - provider body"),
                new org.springframework.ai.retry.NonTransientAiException("HTTP 4010 - provider body"),
                new org.springframework.ai.retry.TransientAiException("HTTP 403"),
                new org.springframework.ai.retry.NonTransientAiException("prefix HTTP 401 - provider body"),
                new org.springframework.ai.retry.TransientAiException("HTTP 500 - body contains HTTP 403 - text"))) {
            AtomicInteger calls = new AtomicInteger();
            RagRetrievalService.Recall embedding = (s, q, f, n, d) -> {
                calls.incrementAndGet(); throw error;
            };
            var service = new RagRetrievalService(registry(), normal, embedding, rank, (s, c) -> true, 2);
            try {
                var result = service.search(request(false, RagFailurePolicy.ALLOW_DEGRADED), List.of());
                assertTrue(result.degraded());
                assertEquals(RagSearchMode.KEYWORD, result.effectiveMode());
                assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE, result.failedStages().get(0).code());
                assertEquals(2, calls.get());
            } finally { service.close(); }
        }
    }
    @Test void emptySuccessAndOldCandidatesNeverReachReranker() {
        AtomicInteger calls = new AtomicInteger();
        var service = new RagRetrievalService(registry(), normal, normal,
                (q, c, p) -> { calls.incrementAndGet(); return c; }, (s, c) -> false, 2);
        try {
            var result = service.search(request(true, RagFailurePolicy.STRICT), List.of());
            assertTrue(result.candidates().isEmpty());
            assertFalse(result.degraded());
            assertEquals(0, calls.get());
            assertTrue(result.stageStats().stream().allMatch(stat -> stat.candidateCount() == 0));
        } finally { service.close(); }
    }
    @Test void rerankFailureDegradesToFusionNotSemantic() {
        var service = new RagRetrievalService(registry(), normal, normal,
                (q, c, p) -> { throw new RagException(RagErrorCode.RERANK_PROTOCOL_ERROR); }, (s, c) -> true, 2);
        try {
            var result = service.search(request(true, RagFailurePolicy.ALLOW_DEGRADED), List.of());
            assertTrue(result.degraded());
            assertEquals(RagSearchMode.HYBRID, result.effectiveMode());
            assertEquals(RagScoreKind.RANK_FUSION, result.candidates().get(0).scoreKind());
        } finally { service.close(); }
    }
    @Test void mandatoryScopeReachesBothRoutesAndUnknownFiltersAreRejectedBeforeExecution() {
        AtomicInteger calls = new AtomicInteger();
        var owner = new RagFilter("ownerId", RagFilterOperator.EQ, RagValue.string("a"));
        RagRetrievalService.Recall recall = (s, q, f, n, d) -> { assertTrue(f.contains(owner)); calls.incrementAndGet(); return List.of(); };
        var service = new RagRetrievalService(registry(), recall, recall, rank, (s, c) -> true, 2);
        try {
            service.search(request(false, RagFailurePolicy.STRICT), List.of(owner));
            assertEquals(2, calls.get());
            assertThrows(RagException.class, () -> service.search(request(false, RagFailurePolicy.STRICT),
                    List.of(new RagFilter("nativeFilter", RagFilterOperator.EQ, RagValue.string("*")))));
            assertEquals(2, calls.get());
        } finally { service.close(); }
    }
    @Test void knownProbeDownStillAllowsOnlyExplicitDegradedRuntimeRecall() {
        List<com.bkanent.rag.config.RagCapabilityProbe> probes = new ArrayList<>();
        for (RagStage stage : List.of(RagStage.KEYWORD, RagStage.VECTOR, RagStage.EMBEDDING, RagStage.RERANK)) {
            probes.add(new com.bkanent.rag.config.RagCapabilityProbe() {
                public RagStage stage() { return stage; }
                public void inspect(com.bkanent.rag.storage.RagSpaceDefinition space) {
                    if (stage == RagStage.KEYWORD) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
                }
            });
        }
        var managed = new com.bkanent.rag.config.ManagedRagSpaceRegistry(
                new com.bkanent.rag.config.RagProperties(Map.of(space().spaceId(), space())), () -> probes, java.time.Clock.systemUTC());
        assertFalse(managed.inspect("inspect", space().spaceId()).ready());
        var service = new RagRetrievalService(managed, (s, q, f, n, d) -> { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }, normal, rank, (s, c) -> true, 2);
        try {
            assertThrows(RagException.class, () -> service.search(request(false, RagFailurePolicy.STRICT), List.of()));
            assertEquals(RagSearchMode.VECTOR, service.search(request(false, RagFailurePolicy.ALLOW_DEGRADED), List.of()).effectiveMode());
        } finally { service.close(); }
    }
    @Test void oneSharedDeadlineAndHardConcurrencyRejectUnboundedWork() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        RagRetrievalService.Recall blocked = (s, q, f, n, d) -> {
            calls.incrementAndGet();
            // Model an uninterruptible library read. Caller timeout cannot create replacement workers.
            while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException ignored) { } }
            return List.of();
        };
        var service = new RagRetrievalService(registry(), blocked, blocked, rank, (s, c) -> true, 2);
        long start = System.nanoTime();
        try {
            assertThrows(RagException.class, () -> service.search(request(false, RagFailurePolicy.STRICT), List.of()));
            assertTrue((System.nanoTime() - start) / 1000000 < 700);
            assertThrows(RagException.class, () -> service.search(request(false, RagFailurePolicy.STRICT), List.of()));
            assertEquals(2, calls.get());
        } finally { release.countDown(); service.close(); }
    }
}
