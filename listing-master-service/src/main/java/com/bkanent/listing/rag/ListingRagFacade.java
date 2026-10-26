package com.bkanent.listing.rag;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagRetrievalRpcService;
import com.bkanent.listing.converter.ListingConverter;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.mapper.ListingMapper;
import com.bkanent.listing.search.ListingKnowledgeDocumentAssembler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/** Authorized candidates are only hints; all externally returned fields come from latest SQL. */
@Service
public class ListingRagFacade {
    private final ListingRagIdentity identity;
    private final ListingRagGrantService grants;
    private final ObjectProvider<RagRetrievalRpcService> rpc;
    private final ListingMapper listings;
    private final ListingConverter converter;
    private final ListingKnowledgeDocumentAssembler assembler;
    private final ListingRagOutbox outbox;
    public ListingRagFacade(ListingRagIdentity identity, ListingRagGrantService grants,
                            @Qualifier("ragRetrievalRpcService") ObjectProvider<RagRetrievalRpcService> rpc, ListingMapper listings,
                            ListingConverter converter, ListingKnowledgeDocumentAssembler assembler, ListingRagOutbox outbox) {
        this.identity = identity; this.grants = grants; this.rpc = rpc; this.listings = listings;
        this.converter = converter; this.assembler = assembler; this.outbox = outbox;
    }
    public RagListingSearchResponse search(RagListingSearchRequest input, RagCredentialContext credentials, String expectedActor) {
        AuthRagPrincipal principal = identity.requireReader(credentials);
        if (expectedActor != null && !expectedActor.equals(String.valueOf(principal.userId()))) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        RagSearchRequest request = request(input);
        RagRetrievalRpcService provider = rpc.getIfAvailable();
        if (provider == null) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
        List<RagListingMatch> matches = new ArrayList<>();
        RagSearchResponse response = null;
        int usedBudget = 0;
        int totalBudget = Math.max(500, request.recall().keywordCandidates() + request.recall().vectorCandidates());
        LinkedHashMap<String, RagCandidate> accumulated = new LinkedHashMap<>();
        for (int attempt = 0; attempt < 2; attempt++) {
            usedBudget += request.recall().keywordCandidates() + request.recall().vectorCandidates();
            if (usedBudget > totalBudget) { break; }
            RagAuthorizationScope scope = grants.scope(principal, request);
            RagCredentialContext retrievalCredentials = principal.versionedAuthorization() ? null : credentials;
            try {
                response = provider.search(request, identity.sign("rag-service", RagOperation.SEARCH, request.requestId(),
                        RagCanonicalHash.requestHash(request), RagActorMode.USER, String.valueOf(principal.userId()), retrievalCredentials),
                        retrievalCredentials, scope);
            } catch (RagException e) { throw e; }
            catch (RuntimeException e) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
            if (response == null || !request.requestId().equals(response.requestId()) || !ListingRagIdentity.SPACE.equals(response.spaceId())
                    || response.candidates().size() > request.candidateLimit()) {
                throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
            }
            for (RagCandidate candidate : response.candidates()) { accumulated.put(candidate.documentId(), candidate); }
            // Re-resolve current role/status/revocation after retrieval, not just at request start.
            AuthRagPrincipal currentPrincipal = identity.requireReader(credentials);
            if (!principal.userId().equals(currentPrincipal.userId())) { throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }
            principal = currentPrincipal;
            matches = latest(List.copyOf(accumulated.values()), principal, input);
            int topK = input.topK() == null ? 10 : input.topK();
            if (matches.size() >= topK || attempt == 1 || response.candidates().size() < request.candidateLimit()) { break; }
            int remaining = totalBudget - usedBudget;
            int perRoute = Math.min(200, remaining / 2);
            if (perRoute <= request.recall().keywordCandidates() || perRoute <= request.recall().vectorCandidates()) { break; }
            int limit = Math.min(200, Math.max(topK, request.candidateLimit() * 2));
            request = new RagSearchRequest(1, UUID.randomUUID().toString(), ListingRagIdentity.SPACE, input.query(), request.filters(),
                    new RagRecallOptions(true, null, perRoute, perRoute), null, true, perRoute * 2, limit, RagFailurePolicy.STRICT).normalized();
        }
        int topK = input.topK() == null ? 10 : input.topK();
        List<RagListingMatch> result = matches.stream().limit(topK).toList();
        String context = result.stream().map(m -> assembler.assemble(m.listing()).content()).collect(Collectors.joining("\n"));
        // Do not leak rejected RAG candidates via diagnostic metadata or their stale content.
        RagSearchResponse safe = response == null ? null : new RagSearchResponse(response.contractVersion(), response.requestId(), response.spaceId(),
                response.strategyVersion(), response.requestedMode(), response.effectiveMode(), response.degraded(), response.failedStages(),
                result.stream().map(RagListingMatch::candidate).toList(), List.of());
        return new RagListingSearchResponse(1, input.requestId(), result, context, safe);
    }
    public static RagSearchRequest request(RagListingSearchRequest input) {
        int keyword = input.keywordTopK() == null ? 20 : input.keywordTopK();
        int vector = input.vectorTopK() == null ? 20 : input.vectorTopK();
        int limit = input.topK() == null ? 10 : input.topK();
        if (keyword < 1 || keyword > 500 || vector < 1 || vector > 500 || limit < 1 || limit > 200
                || limit > keyword + vector) { throw new RagException(RagErrorCode.INVALID_ARGUMENT, "quantities"); }
        checkRange(input.minArea(), input.maxArea()); checkRange(input.minTotalPrice(), input.maxTotalPrice());
        List<RagFilter> filters = new ArrayList<>();
        if (input.region() != null && !input.region().isBlank()) { filters.add(new RagFilter("region", RagFilterOperator.CONTAINS_CI, RagValue.string(input.region()))); }
        if (input.layout() != null && !input.layout().isBlank()) { filters.add(new RagFilter("layout", RagFilterOperator.EQ, RagValue.string(input.layout()))); }
        range(filters, "area", input.minArea(), input.maxArea()); range(filters, "totalPrice", input.minTotalPrice(), input.maxTotalPrice());
        return new RagSearchRequest(1, input.requestId(), ListingRagIdentity.SPACE, input.query(), filters,
                new RagRecallOptions(true, null, keyword, vector), null, true, Math.min(500, keyword + vector), limit, RagFailurePolicy.STRICT).normalized();
    }
    private List<RagListingMatch> latest(List<RagCandidate> candidates, AuthRagPrincipal principal, RagListingSearchRequest input) {
        LinkedHashMap<Long, RagCandidate> byId = new LinkedHashMap<>();
        for (RagCandidate candidate : candidates) {
            try {
                Long id = Long.valueOf(candidate.sourceId());
                if ("LISTING".equals(candidate.sourceType()) && RagDocumentIds.listing(id).equals(candidate.documentId())) { byId.putIfAbsent(id, candidate); }
            } catch (RuntimeException ignored) { /* reject invalid identity, never trust metadata ID */ }
        }
        if (byId.isEmpty()) { return List.of(); }
        Map<Long, ListingEntity> current = listings.selectBatchIds(byId.keySet()).stream().collect(Collectors.toMap(ListingEntity::getId, e -> e));
        Map<Long, ListingRagOutbox.Revision> revisions = outbox.revisions(byId.keySet());
        List<RagListingMatch> result = new ArrayList<>();
        byId.forEach((id, candidate) -> {
            ListingEntity entity = current.get(id);
            ListingRagOutbox.Revision revision = revisions.get(id);
            if (!ListingRagIdentity.canRead(principal, entity) || revision == null || revision.tombstone()
                    || revision.revision() != candidate.projectionVersion() || !matches(entity, input)) { return; }
            var dto = converter.toDto(entity);
            RagCandidate safe = new RagCandidate(candidate.documentId(), "LISTING", String.valueOf(id), assembler.assemble(dto).content(),
                    candidate.projectionVersion(), candidate.schemaVersion(), candidate.sources(), candidate.keywordScore(), candidate.vectorScore(),
                    candidate.fusionScore(), candidate.rawRerankScore(), candidate.relevanceScore(), candidate.scoreKind(), candidate.scoringVersion(), Map.of());
            result.add(new RagListingMatch(dto, safe.keywordScore(), safe.vectorScore(), safe.rawRerankScore(), safe.relevanceScore(), safe.sources(), safe));
        });
        return result;
    }
    private static boolean matches(ListingEntity e, RagListingSearchRequest r) {
        return (r.region() == null || r.region().isBlank() || (e.getAddress() != null && e.getAddress().toLowerCase(Locale.ROOT).contains(r.region().toLowerCase(Locale.ROOT))))
                && (r.layout() == null || r.layout().isBlank() || r.layout().equals(e.getLayout()))
                && within(e.getArea(), r.minArea(), r.maxArea()) && within(e.getTotalPrice(), r.minTotalPrice(), r.maxTotalPrice());
    }
    private static boolean within(BigDecimal value, BigDecimal min, BigDecimal max) {
        return (min == null || value != null && value.compareTo(min) >= 0) && (max == null || value != null && value.compareTo(max) <= 0);
    }
    private static void range(List<RagFilter> f, String field, BigDecimal min, BigDecimal max) {
        if (min != null) { f.add(new RagFilter(field, RagFilterOperator.GTE, RagValue.number(min))); }
        if (max != null) { f.add(new RagFilter(field, RagFilterOperator.LTE, RagValue.number(max))); }
    }
    private static void checkRange(BigDecimal min, BigDecimal max) {
        if (min != null && min.signum() < 0 || max != null && max.signum() < 0 || min != null && max != null && min.compareTo(max) > 0) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "range");
        }
    }
}
