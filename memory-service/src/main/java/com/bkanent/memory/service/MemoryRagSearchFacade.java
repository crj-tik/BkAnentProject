package com.bkanent.memory.service;

import com.bkanent.common.agent.SystemConstraintRecord;
import com.bkanent.common.agent.UserPreferenceRecord;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagRetrievalRpcService;
import com.bkanent.memory.config.MemoryRagProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

/** Retrieval returns candidates; SQL alone determines current owner, availability and final order. */
@Service
public class MemoryRagSearchFacade {
    public static final String SCORING = "memory-confidence-semantic-decay-v1";
    private static final Set<String> PREFERENCE_RETURN_FIELDS = Set.of("preferenceKey", "category", "confidence", "observationCount", "lastObservedAt", "active");
    private static final Set<String> CONSTRAINT_RETURN_FIELDS = Set.of("constraintKey", "category", "tags", "active");
    private final RagRetrievalRpcService retrieval;
    private final MemoryRagSecurity security;
    private final JdbcTemplate jdbc;
    private final MemoryRagProperties properties;
    private final MemoryRagProjectionService projection;
    public MemoryRagSearchFacade(RagRetrievalRpcService retrieval, MemoryRagSecurity security, JdbcTemplate jdbc, MemoryRagProperties properties, MemoryRagProjectionService projection) {
        this.retrieval = retrieval; this.security = security; this.jdbc = jdbc; this.properties = properties; this.projection = projection;
    }

    public MemoryRagSearchResponse search(MemoryRagSearchRequest domain, AuthRagPrincipal principal, RagCredentialContext credentials) {
        AuthRagPrincipal original = principal;
        RagSearchRequest request = domain.search().normalized();
        if (domain.topK() > request.candidateLimit()) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "topK");
        validateFilters(request);
        if (domain.halfLifeDays() != null && !MemoryRagProjectionService.PREFERENCE.equals(request.spaceId())) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "halfLifeDays");
        security.scope(request, principal); // Unknown space/read role fails before any SQL or remote retrieval.
        Instant asOf = Instant.now();
        int initialRecall = recallCount(request);
        int remainingRecall = Math.max(500, initialRecall) - initialRecall;
        RagSearchResponse response = call(request, principal, credentials);
        Map<String, RagCandidate> pool = new LinkedHashMap<>();
        merge(pool, response.candidates());
        principal = currentPrincipal(principal, credentials, request);
        List<MemoryRagMatch> matches = finalTruth(List.copyOf(pool.values()), request, domain, principal, asOf);
        if (matches.size() < domain.topK() && response.candidates().size() == request.candidateLimit()
                && pool.size() > matches.size() && request.candidateLimit() < 200) {
            RagSearchRequest expanded = expand(request);
            if (recallCount(expanded) <= remainingRecall) {
                request = expanded;
                response = call(request, principal, credentials);
                merge(pool, response.candidates());
                // Recheck all survivors under the writer lock: the second RPC may invalidate the first pool.
                principal = currentPrincipal(principal, credentials, request);
                matches = finalTruth(List.copyOf(pool.values()), request, domain, principal, asOf);
            }
        }
        Map<String, RagCandidate> rankedPool = new LinkedHashMap<>();
        merge(rankedPool, response.candidates());
        pool.forEach(rankedPool::putIfAbsent);
        List<RagCandidate> responseOrder = List.copyOf(rankedPool.values());
        matches = matches.stream().sorted(Comparator.comparingDouble(MemoryRagMatch::domainScore).reversed()
                .thenComparingInt(match -> responseRank(match.candidate(), responseOrder))
                .thenComparing(match -> match.candidate().documentId())).limit(domain.topK()).toList();
        RagSearchResponse safe = new RagSearchResponse(response.contractVersion(), response.requestId(), response.spaceId(), response.strategyVersion(), response.requestedMode(), response.effectiveMode(), response.degraded(), response.failedStages(), matches.stream().map(MemoryRagMatch::candidate).toList(), List.of());
        // Final-truth SQL may wait on a writer lock after the earlier authentication.
        currentPrincipal(original, credentials, request);
        return new MemoryRagSearchResponse(safe, matches, SCORING);
    }

    private AuthRagPrincipal currentPrincipal(AuthRagPrincipal original, RagCredentialContext credentials, RagSearchRequest request) {
        AuthRagPrincipal current = security.authenticate(credentials);
        if (!current.userId().equals(original.userId())) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        security.scope(request, current);
        return current;
    }

    private int responseRank(RagCandidate candidate, List<RagCandidate> candidates) {
        for (int i = 0; i < candidates.size(); i++) if (candidates.get(i).documentId().equals(candidate.documentId())) return i;
        return Integer.MAX_VALUE;
    }

    private RagSearchResponse call(RagSearchRequest request, AuthRagPrincipal principal, RagCredentialContext credentials) {
        try {
            var result = retrieval.search(request, security.searchAuthentication(request, principal, credentials), credentials, security.scope(request, principal));
            if (result == null || !request.requestId().equals(result.requestId()) || !request.spaceId().equals(result.spaceId()) || result.candidates().size() > request.candidateLimit()) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
            return result;
        } catch (RagException ex) { throw ex; }
        catch (RuntimeException ex) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
    }

    private List<MemoryRagMatch> finalTruth(List<RagCandidate> candidates, RagSearchRequest request, MemoryRagSearchRequest domain, AuthRagPrincipal principal, Instant asOf) {
        // Hold the same domain writer lock for the bounded SQL check only, never during RAG calls.
        return new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())))
                .execute(status -> finalTruthLocked(candidates, request, domain, principal, asOf));
    }

    private List<MemoryRagMatch> finalTruthLocked(List<RagCandidate> candidates, RagSearchRequest request, MemoryRagSearchRequest domain, AuthRagPrincipal principal, Instant asOf) {
        jdbc.queryForObject("SELECT lock_id FROM memory_rag_lock WHERE lock_id=1 FOR UPDATE", Integer.class);
        Map<String, UserPreferenceRecord> preferences = new HashMap<>();
        Map<String, SystemConstraintRecord> constraints = new HashMap<>();
        var named = new NamedParameterJdbcTemplate(jdbc);
        if (MemoryRagProjectionService.PREFERENCE.equals(request.spaceId())) {
            List<String> keys = candidates.stream().map(c -> c.metadata().get("preferenceKey")).filter(Objects::nonNull).map(RagValue::scalar).distinct().toList();
            if (!keys.isEmpty()) named.query("SELECT * FROM user_preference_memory WHERE user_id=:owner AND preference_key IN (:keys) AND confidence>=0.1 AND deleted=0", Map.of("owner", principal.userId().toString(), "keys", keys), UserPreferenceMemoryService.ROW).forEach(r -> preferences.put(RagDocumentIds.preference(r.userId(), r.preferenceKey()), r));
        } else {
            List<String> keys = candidates.stream().map(RagCandidate::sourceId).distinct().toList();
            if (!keys.isEmpty()) named.query("SELECT * FROM system_constraint_memory WHERE constraint_key IN (:keys) AND active=TRUE AND deleted=0", Map.of("keys", keys), SystemConstraintMemoryService.ROW).forEach(r -> constraints.put(RagDocumentIds.constraint(r.constraintKey()), r));
        }
        List<MemoryRagMatch> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RagCandidate candidate : candidates) {
            if (!seen.add(candidate.documentId())) continue;
            var preference = preferences.get(candidate.documentId());
            var constraint = constraints.get(candidate.documentId());
            if (preference == null && constraint == null) continue;
            var current = jdbc.queryForList("SELECT revision,tombstone,event_payload FROM memory_rag_projection WHERE space_id=? AND document_id=?", request.spaceId(), candidate.documentId());
            if (current.size() != 1 || ((Number) current.get(0).get("revision")).longValue() != candidate.projectionVersion() || Boolean.parseBoolean(current.get(0).get("tombstone").toString()) || "1".equals(current.get(0).get("tombstone").toString())) continue;
            RagDocument document = preference != null ? MemoryRagProjectionService.preferenceDocument(preference) : MemoryRagProjectionService.constraintDocument(constraint);
            RagIndexEvent stored = projection.decode(jdbc.queryForObject("SELECT event_payload FROM memory_rag_projection WHERE space_id=? AND document_id=?", String.class, request.spaceId(), candidate.documentId()));
            if (!stored.contentHash().equals(RagCanonicalHash.documentHash(RagIndexOperation.UPSERT, document))) continue;
            Map<String, RagValue> actual = new HashMap<>();
            if (preference != null) {
                actual.put("preferenceKey", RagValue.string(preference.preferenceKey()));
                if (preference.category() != null) actual.put("category", RagValue.string(preference.category()));
                actual.put("confidence", RagValue.number(preference.confidence()));
                if (preference.lastObservedAt() != null) actual.put("lastObservedAt", RagValue.instant(preference.lastObservedAt().toInstant(ZoneOffset.UTC)));
            } else {
                actual.put("constraintKey", RagValue.string(constraint.constraintKey()));
                actual.put("active", RagValue.bool(constraint.active()));
                if (constraint.category() != null) actual.put("category", RagValue.string(constraint.category()));
            }
            if (!request.filters().stream().allMatch(filter -> matches(actual.get(filter.field()), filter))) continue;
            boolean semantic = candidate.scoreKind() == RagScoreKind.SEMANTIC_NORMALIZED;
            if (domain.minimumSemanticRelevance() != null || semantic) {
                if (!semantic || candidate.scoringVersion() == null || !properties.getSemanticScoringVersions().contains(candidate.scoringVersion()) || candidate.relevanceScore() == null || !Double.isFinite(candidate.relevanceScore()) || candidate.relevanceScore() < 0 || candidate.relevanceScore() > 1) throw new RagException(RagErrorCode.SCORING_PROFILE_MISMATCH);
                if (domain.minimumSemanticRelevance() != null && candidate.relevanceScore() < domain.minimumSemanticRelevance()) continue;
            }
            double score = semantic ? candidate.relevanceScore() : 1;
            if (preference != null) {
                score *= preference.confidence().doubleValue();
                if (domain.halfLifeDays() != null && preference.lastObservedAt() != null) {
                    double days = Math.max(0, Duration.between(preference.lastObservedAt().toInstant(ZoneOffset.UTC), asOf).toMillis() / 86400000.0);
                    score *= Math.pow(2, -days / domain.halfLifeDays());
                }
            }
            Set<String> returnFields = preference != null ? PREFERENCE_RETURN_FIELDS : CONSTRAINT_RETURN_FIELDS;
            Map<String, RagValue> metadata = new LinkedHashMap<>();
            document.attributes().forEach((field, value) -> { if (returnFields.contains(field)) metadata.put(field, value); });
            RagCandidate safeCandidate = new RagCandidate(candidate.documentId(), document.sourceType(), document.sourceId(), document.content(), stored.revision(), stored.schemaVersion(), candidate.sources(), candidate.keywordScore(), candidate.vectorScore(), candidate.fusionScore(), candidate.rawRerankScore(), candidate.relevanceScore(), candidate.scoreKind(), candidate.scoringVersion(), metadata);
            results.add(new MemoryRagMatch(safeCandidate, preference, constraint, score));
        }
        return results;
    }

    private void validateFilters(RagSearchRequest request) {
        Map<String, RagValueType> fields = MemoryRagProjectionService.PREFERENCE.equals(request.spaceId())
                ? Map.of("category", RagValueType.STRING, "preferenceKey", RagValueType.STRING, "confidence", RagValueType.NUMBER, "lastObservedAt", RagValueType.INSTANT)
                : Map.of("category", RagValueType.STRING, "constraintKey", RagValueType.STRING, "active", RagValueType.BOOLEAN);
        for (RagFilter filter : request.filters()) {
            var type = fields.get(filter.field());
            boolean valid = type != null && (filter.op() == RagFilterOperator.EQ && filter.value().type() == type
                    || filter.op() == RagFilterOperator.IN && type == RagValueType.STRING && filter.value().type() == RagValueType.STRING_LIST
                    || (filter.op() == RagFilterOperator.GTE || filter.op() == RagFilterOperator.LTE) && (type == RagValueType.NUMBER || type == RagValueType.INSTANT) && filter.value().type() == type);
            if (!valid || "active".equals(filter.field()) && !"true".equals(filter.value().scalar())) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "filters");
        }
    }

    private boolean matches(RagValue actual, RagFilter filter) {
        if (actual == null) return false;
        return switch (filter.op()) {
            case EQ -> actual.scalar().equals(filter.value().scalar());
            case IN -> filter.value().values().contains(actual.scalar());
            case GTE -> compare(actual, filter.value()) >= 0;
            case LTE -> compare(actual, filter.value()) <= 0;
            default -> false;
        };
    }
    private int compare(RagValue left, RagValue right) {
        return left.type() == RagValueType.INSTANT ? Instant.parse(left.scalar()).compareTo(Instant.parse(right.scalar())) : new BigDecimal(left.scalar()).compareTo(new BigDecimal(right.scalar()));
    }

    private void merge(Map<String, RagCandidate> pool, List<RagCandidate> candidates) {
        for (RagCandidate candidate : candidates) pool.put(candidate.documentId(), candidate);
    }

    private int recallCount(RagSearchRequest request) {
        RagRecallOptions recall = request.recall();
        if (recall.hybrid()) return recall.keywordCandidates() + recall.vectorCandidates();
        return recall.singleRecall() == RagRecallMode.KEYWORD ? recall.keywordCandidates() : recall.vectorCandidates();
    }

    private RagSearchRequest expand(RagSearchRequest original) {
        int limit = Math.min(200, original.candidateLimit() * 2);
        boolean rerank = original.semanticRerank();
        int pool = rerank ? Math.max(limit, original.rerankCandidates()) : limit;
        RagRecallOptions recall = original.recall();
        if (recall.hybrid()) recall = new RagRecallOptions(true, null, Math.max(recall.keywordCandidates(), (pool + 1) / 2), Math.max(recall.vectorCandidates(), (pool + 1) / 2));
        else recall = new RagRecallOptions(false, recall.singleRecall(), recall.singleRecall() == RagRecallMode.KEYWORD ? Math.max(recall.keywordCandidates(), pool) : null, recall.singleRecall() == RagRecallMode.VECTOR ? Math.max(recall.vectorCandidates(), pool) : null);
        return new RagSearchRequest(1, UUID.randomUUID().toString(), original.spaceId(), original.query(), original.filters(), recall, original.fusion(), rerank, rerank ? pool : null, limit, original.failurePolicy()).normalized();
    }
}
