package com.bkanent.rag.retrieval;

import com.bkanent.common.rag.*;
import com.bkanent.rag.index.RagIndexService;
import com.bkanent.rag.rerank.BgeRerankService;
import com.bkanent.rag.security.http.RagHttpDeadline;
import com.bkanent.rag.storage.*;
import com.bkanent.common.rag.MemoryRagAuthorizationScope;
import com.bkanent.common.rag.RagCandidateAuthorizationRequest;
import com.bkanent.common.rag.RagCandidateReference;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiPredicate;
import java.util.Set;

/** Authorized bounded execution; caller-supplied filters can only narrow mandatory scope. */
@Service
public class RagRetrievalService {
    @FunctionalInterface public interface Recall {
        List<RecallHit> search(RagSpaceDefinition space, String query, List<RagFilter> filters, int limit, Duration remaining);
    }
    @FunctionalInterface public interface Rerank {
        List<RagCandidate> apply(String query, List<RagCandidate> candidates, com.bkanent.rag.rerank.RerankProfile profile);
    }
    @FunctionalInterface public interface CandidateAuthorizer {
        Set<String> authorize(RagCandidateAuthorizationRequest request, MemoryRagAuthorizationScope scope);
    }
    private final RagSpaceRegistry registry;
    private final Recall keyword;
    private final Recall vector;
    private final Rerank rerank;
    private final BiPredicate<String, RagCandidate> visible;
    private final CandidateAuthorizer candidateAuthorizer;
    private final ThreadPoolExecutor workers;
    private final RrfFusion fusion = new RrfFusion();

    @org.springframework.beans.factory.annotation.Autowired
    public RagRetrievalService(RagSpaceRegistry registry, ElasticsearchProjectionStore keyword,
            SpringAiMilvusDocumentStore vector, BgeRerankService rerank, RagIndexService ledger,
            CandidateAuthorizer candidateAuthorizer,
            @Value("${rag.execution.max-concurrency:8}") int concurrency) {
        this(registry, keyword::search, (s, q, f, n, d) -> vector.search(s, q, f, n), rerank::rerank,
                ledger::visible, candidateAuthorizer, concurrency);
    }

    public RagRetrievalService(RagSpaceRegistry registry, Recall keyword, Recall vector,
            Rerank rerank, BiPredicate<String, RagCandidate> visible, int concurrency) {
        this(registry, keyword, vector, rerank, visible, null, concurrency);
    }

    public RagRetrievalService(RagSpaceRegistry registry, Recall keyword, Recall vector,
            Rerank rerank, BiPredicate<String, RagCandidate> visible, CandidateAuthorizer candidateAuthorizer,
            int concurrency) {
        this.registry = registry; this.keyword = keyword; this.vector = vector;
        this.rerank = rerank; this.visible = visible; this.candidateAuthorizer = candidateAuthorizer;
        if (concurrency < 2 || concurrency > 64) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "maxConcurrency");
        workers = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(concurrency),
                task -> { Thread thread = new Thread(task, "rag-retrieval"); thread.setDaemon(true); return thread; },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** Authentication/scope verification occurs in RPC before this non-public-network execution entry. */
    public RagSearchResponse search(RagSearchRequest request, List<RagFilter> mandatoryFilters) {
        return search(request, mandatoryFilters, null);
    }

    /** Memory v2 scope is passed only by the dedicated native RPC path. */
    public RagSearchResponse search(RagSearchRequest request, List<RagFilter> mandatoryFilters,
                                    MemoryRagAuthorizationScope memoryScope) {
        RagSearchRequest normalized = request.normalized();
        Set<RagStage> requiredStages = EnumSet.noneOf(RagStage.class);
        if (normalized.recall().hybrid() || normalized.recall().singleRecall() == RagRecallMode.KEYWORD) requiredStages.add(RagStage.KEYWORD);
        if (normalized.recall().hybrid() || normalized.recall().singleRecall() == RagRecallMode.VECTOR) {
            requiredStages.add(RagStage.VECTOR); requiredStages.add(RagStage.EMBEDDING);
        }
        if (normalized.semanticRerank()) requiredStages.add(RagStage.RERANK);
        RagSpaceDefinition space = registry.requireReady(normalized.spaceId(), requiredStages, normalized.failurePolicy());
        List<RagFilter> filters = new ArrayList<>(Objects.requireNonNull(mandatoryFilters));
        // contains_ci may postfilter content, but it must never be the sole backend authorization scope.
        for (RagFilter mandatory : mandatoryFilters) {
            if (mandatory.op() == RagFilterOperator.CONTAINS_CI) throw new RagException(RagErrorCode.FORBIDDEN);
        }
        filters.addAll(normalized.filters());
        ProjectionFilters.validate(space, filters);
        validateLimits(normalized, space);
        long deadline = RagHttpDeadline.capture(Duration.ofMillis(space.limits().overallTimeoutMillis()));
        boolean useKeyword = normalized.recall().hybrid() || normalized.recall().singleRecall() == RagRecallMode.KEYWORD;
        boolean useVector = normalized.recall().hybrid() || normalized.recall().singleRecall() == RagRecallMode.VECTOR;
        List<RagStageFailure> failures = new ArrayList<>();
        List<RagStageStats> stats = new ArrayList<>();
        Future<StageResult<List<RecallHit>>> k = null;
        Future<StageResult<List<RecallHit>>> v = null;
        try {
            long recallDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(space.limits().recallTimeoutMillis()));
            if (useKeyword) k = submit(RagStage.KEYWORD, recallDeadline,
                    () -> keyword.search(space, normalized.query(), filters, normalized.recall().keywordCandidates(), remaining(recallDeadline)));
            if (useVector) v = submit(RagStage.VECTOR, recallDeadline,
                    () -> vector.search(space, normalized.query(), filters, normalized.recall().vectorCandidates(), remaining(recallDeadline)));
            StageResult<List<RecallHit>> keywordResult = useKeyword ? await(k, RagStage.KEYWORD, recallDeadline) : null;
            StageResult<List<RecallHit>> vectorResult = useVector ? await(v, RagStage.VECTOR, recallDeadline) : null;
            List<RecallHit> rawKeywords = result(keywordResult, normalized.failurePolicy(), failures, stats);
            List<RecallHit> rawVectors = result(vectorResult, normalized.failurePolicy(), failures, stats);
            StageResult<List<List<RecallHit>>> checked = await(submit(RagStage.LEDGER, deadline,
                    () -> List.of(authorized(space, rawKeywords), authorized(space, rawVectors))), RagStage.LEDGER, deadline);
            if (checked.failure != null) throw classify(checked.failure);
            List<RecallHit> keywords = checked.value.get(0);
            List<RecallHit> vectors = checked.value.get(1);
            if (keywordResult != null && keywordResult.failure == null) stats.add(new RagStageStats(RagStage.KEYWORD, keywords.size(), keywordResult.elapsed));
            if (vectorResult != null && vectorResult.failure == null) stats.add(new RagStageStats(RagStage.VECTOR, vectors.size(), vectorResult.elapsed));
            boolean effectiveKeyword = useKeyword && keywordResult.failure == null;
            boolean effectiveVector = useVector && vectorResult.failure == null;
            if (!effectiveKeyword && !effectiveVector) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
            requireTime(deadline);
            long recallBytes = bytesHits(keywords) + bytesHits(vectors);
            if (recallBytes > space.limits().maxRecallUtf8Bytes()) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "recallTextBudget");
            long started = System.nanoTime();
            List<RagCandidate> candidates = fusion.fuse(keywords, vectors, normalized.fusion(), effectiveKeyword, effectiveVector,
                    normalized.semanticRerank() ? normalized.rerankCandidates() : normalized.candidateLimit());
            stats.add(new RagStageStats(RagStage.FUSION, candidates.size(), elapsed(started)));
            if (space.candidateAuthorizationRequired() && !candidates.isEmpty()) {
                if (candidateAuthorizer == null || memoryScope == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "memoryAuthorization");
                requireTime(deadline);
                candidates = authorizeCandidates(normalized, memoryScope, candidates);
                requireTime(deadline);
            }
            boolean semantic = normalized.semanticRerank();
            if (semantic && !candidates.isEmpty()) {
                long bytes = candidates.stream().mapToLong(c -> c.content().getBytes(StandardCharsets.UTF_8).length).sum()
                        + normalized.query().getBytes(StandardCharsets.UTF_8).length;
                if (bytes > space.limits().maxRerankUtf8Bytes()) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "rerankTextBudget");
                long rerankDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(space.limits().rerankTimeoutMillis()));
                List<RagCandidate> pool = candidates;
                Future<StageResult<List<RagCandidate>>> future = submit(RagStage.RERANK, rerankDeadline,
                        () -> rerank.apply(normalized.query(), pool, space.rerankProfile()));
                StageResult<List<RagCandidate>> ranked = await(future, RagStage.RERANK, rerankDeadline);
                if (ranked.failure != null) {
                    failure(ranked.failure, RagStage.RERANK, normalized.failurePolicy(), failures);
                    semantic = false;
                }
                else { candidates = ranked.value; stats.add(new RagStageStats(RagStage.RERANK, candidates.size(), ranked.elapsed)); }
            }
            requireTime(deadline);
            List<RagCandidate> output = candidates.stream().limit(normalized.candidateLimit())
                    .map(c -> ProjectionCodec.publicCandidate(c, space)).toList();
            return new RagSearchResponse(1, normalized.requestId(), normalized.spaceId(), RrfFusion.VERSION,
                    normalized.requestedMode(), mode(effectiveKeyword, effectiveVector, semantic), !failures.isEmpty(),
                    failures, output, stats);
        }
        finally { if (k != null && !k.isDone()) k.cancel(true); if (v != null && !v.isDone()) v.cancel(true); }
    }

    private List<RagCandidate> authorizeCandidates(RagSearchRequest request, MemoryRagAuthorizationScope scope,
            List<RagCandidate> candidates) {
        List<RagCandidateReference> references = candidates.stream().map(candidate -> {
            RagValue hash = candidate.metadata().get("contentHash");
            if (hash == null || hash.type() != RagValueType.STRING || !hash.scalar().matches("[0-9a-f]{64}")) {
                throw new RagException(RagErrorCode.SPACE_NOT_READY, "candidateHash");
            }
            return new RagCandidateReference(candidate.documentId(), candidate.sourceType(), candidate.sourceId(),
                    candidate.projectionVersion(), candidate.schemaVersion(), hash.scalar());
        }).toList();
        RagCandidateAuthorizationRequest authorization = new RagCandidateAuthorizationRequest(
                RagContract.VERSION, request, references);
        Set<String> permitted;
        try { permitted = candidateAuthorizer.authorize(authorization, scope); }
        catch (RagException failure) { throw failure; }
        catch (RuntimeException failure) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "memoryAuthorization"); }
        if (permitted == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "memoryAuthorization");
        Set<String> requested = references.stream().map(RagCandidateReference::documentId).collect(java.util.stream.Collectors.toSet());
        if (!requested.containsAll(permitted)) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "memoryAuthorization");
        return candidates.stream().filter(candidate -> permitted.contains(candidate.documentId())).toList();
    }

    private List<RecallHit> authorized(RagSpaceDefinition space, List<RecallHit> hits) {
        List<RecallHit> current = new ArrayList<>();
        for (RecallHit hit : hits) {
            try { if (visible.test(space.spaceId(), hit.candidate())) current.add(hit); }
            catch (RuntimeException failure) { throw new LedgerFailure(); }
        }
        return List.copyOf(current);
    }

    private <T> Future<StageResult<T>> submit(RagStage stage, long deadline, Callable<T> action) {
        try {
            return workers.submit(() -> {
                long start = System.nanoTime();
                try (RagHttpDeadline ignored = RagHttpDeadline.until(deadline)) {
                    for (int attempt = 0; ; attempt++) {
                        requireTime(deadline);
                        try { return new StageResult<>(action.call(), null, elapsed(start), stage); }
                        catch (Exception failure) {
                            RagException error = classify(failure);
                            if (attempt == 0 && error.code() == RagErrorCode.DEPENDENCY_UNAVAILABLE && !(failure instanceof LedgerFailure)) continue;
                            return new StageResult<>(null, failure, elapsed(start), stage);
                        }
                    }
                }
                catch (Exception failure) { return new StageResult<>(null, failure, elapsed(start), stage); }
            });
        }
        catch (RejectedExecutionException failure) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
    }

    private <T> StageResult<T> await(Future<StageResult<T>> future, RagStage stage, long deadline) {
        try {
            if (future.isDone()) return future.get();
            return future.get(RagHttpDeadline.remainingNanos(deadline), TimeUnit.NANOSECONDS);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); future.cancel(true); throw new RagException(RagErrorCode.TIMEOUT);
        }
        catch (ExecutionException failure) { return new StageResult<>(null, failure.getCause(), 0, stage); }
        catch (TimeoutException | RagException failure) {
            future.cancel(true); return new StageResult<>(null, new RagException(RagErrorCode.TIMEOUT), 0, stage);
        }
    }
    private List<RecallHit> result(StageResult<List<RecallHit>> result, RagFailurePolicy policy,
                                   List<RagStageFailure> failures, List<RagStageStats> stats) {
        if (result == null) return List.of();
        if (result.failure != null) { failure(result.failure, result.stage, policy, failures); return List.of(); }
        return result.value;
    }
    private void failure(Throwable cause, RagStage stage, RagFailurePolicy policy, List<RagStageFailure> failures) {
        RagException error = classify(cause);
        if (cause instanceof LedgerFailure || policy == RagFailurePolicy.STRICT || !degradable(error.code())) throw error;
        failures.add(new RagStageFailure(stage, error.code()));
    }
    private static RagException classify(Throwable cause) {
        for (Throwable next = cause; next != null; next = next.getCause()) {
            if (next instanceof RagException error) return error;
            if (next instanceof org.springframework.web.client.RestClientResponseException response) {
                if (response.getStatusCode().value() == 401) return new RagException(RagErrorCode.AUTHENTICATION_FAILED);
                if (response.getStatusCode().value() == 403) return new RagException(RagErrorCode.FORBIDDEN);
            }
            if (next instanceof org.springframework.ai.retry.NonTransientAiException
                    || next instanceof org.springframework.ai.retry.TransientAiException) {
                // Spring AI 1.1.2 drops the HTTP cause. Match only its complete fixed status prefix.
                String message = next.getMessage();
                if (message != null && message.startsWith("HTTP 401 - ")) return new RagException(RagErrorCode.AUTHENTICATION_FAILED);
                if (message != null && message.startsWith("HTTP 403 - ")) return new RagException(RagErrorCode.FORBIDDEN);
            }
            if (next instanceof io.grpc.StatusRuntimeException || next instanceof io.grpc.StatusException) {
                io.grpc.Status.Code code = io.grpc.Status.fromThrowable(next).getCode();
                if (code == io.grpc.Status.Code.UNAUTHENTICATED) return new RagException(RagErrorCode.AUTHENTICATION_FAILED);
                if (code == io.grpc.Status.Code.PERMISSION_DENIED) return new RagException(RagErrorCode.FORBIDDEN);
            }
            if (next instanceof io.milvus.exception.ServerException server
                    && server.getCompatibleCode() == io.milvus.grpc.ErrorCode.PermissionDenied
                    || next instanceof io.milvus.exception.MilvusException milvus
                    && Objects.equals(milvus.getStatus(), io.milvus.param.R.Status.PermissionDenied.getCode())) {
                return new RagException(RagErrorCode.FORBIDDEN);
            }
        }
        return new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
    }
    private static boolean degradable(RagErrorCode code) {
        return code == RagErrorCode.DEPENDENCY_UNAVAILABLE || code == RagErrorCode.TIMEOUT
                || code == RagErrorCode.RERANK_PROTOCOL_ERROR || code == RagErrorCode.SCORING_PROFILE_MISMATCH;
    }
    private static void validateLimits(RagSearchRequest request, RagSpaceDefinition space) {
        var l = space.limits();
        if (request.candidateLimit() > l.maxCandidateLimit()
                || (request.rerankCandidates() != null && request.rerankCandidates() > l.maxRerankCandidates())
                || (request.recall().keywordCandidates() != null && request.recall().keywordCandidates() > l.maxRecallCandidates())
                || (request.recall().vectorCandidates() != null && request.recall().vectorCandidates() > l.maxRecallCandidates())) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "spaceLimits");
        }
    }
    private static RagSearchMode mode(boolean k, boolean v, boolean r) {
        if (k && v) return r ? RagSearchMode.HYBRID_RERANK : RagSearchMode.HYBRID;
        if (k) return r ? RagSearchMode.KEYWORD_RERANK : RagSearchMode.KEYWORD;
        return r ? RagSearchMode.VECTOR_RERANK : RagSearchMode.VECTOR;
    }
    private static long bytesHits(List<RecallHit> hits) { return hits.stream().mapToLong(h -> h.candidate().content().getBytes(StandardCharsets.UTF_8).length).sum(); }
    private static Duration remaining(long deadline) { return Duration.ofNanos(RagHttpDeadline.remainingNanos(deadline)); }
    private static void requireTime(long deadline) { RagHttpDeadline.remainingNanos(deadline); }
    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private record StageResult<T>(T value, Throwable failure, long elapsed, RagStage stage) { }
    private static final class LedgerFailure extends RuntimeException { }
    @PreDestroy public void close() { workers.shutdownNow(); }
}
