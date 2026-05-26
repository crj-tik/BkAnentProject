package com.bkanent.agent.milvus.listing;

import com.bkanent.agent.config.ListingRagProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * BgeRerankService 服务类。
 */
@Service
public class BgeRerankService implements ListingRerankService {

    /**
     * 字段：listingRagProperties。
     */
    private final ListingRagProperties listingRagProperties;
    /**
     * 字段：restClient。
     */
    private final RestClient restClient;

    /**
     * 处理BgeRerankService。
     */
    public BgeRerankService(ListingRagProperties listingRagProperties) {
        this.listingRagProperties = listingRagProperties;
        this.restClient = RestClient.builder().build();
    }

    /**
     * 重排序。
     */
    @Override
    public List<ListingRecallCandidate> rerank(String query, List<ListingRecallCandidate> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (!StringUtils.hasText(listingRagProperties.getRerankEndpoint())) {
            throw new IllegalStateException("agent.rag.rerank-endpoint must be configured for BGE rerank.");
        }
        List<String> documents = candidates.stream()
                .map(ListingRecallCandidate::getContent)
                .toList();
        BgeRerankResponse response = restClient.post()
                .uri(listingRagProperties.getRerankEndpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyHeaders(headers, listingRagProperties.getRerankApiKey()))
                .body(new BgeRerankRequest(
                        listingRagProperties.getRerankModel(),
                        query,
                        documents,
                        Math.min(topK, candidates.size()),
                        true
                ))
                .retrieve()
                .body(BgeRerankResponse.class);
        if (response == null || response.results() == null) {
            return List.of();
        }
        List<ListingRecallCandidate> reranked = new ArrayList<>();
        for (BgeRerankResult result : response.results()) {
            if (result.index() != null && result.index() >= 0 && result.index() < candidates.size()) {
                ListingRecallCandidate candidate = candidates.get(result.index());
                candidate.setRerankScore(result.relevanceScore());
                reranked.add(candidate);
            }
        }
        return reranked.stream()
                .sorted(Comparator.comparing(ListingRecallCandidate::getRerankScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(1, topK))
                .toList();
    }

    /**
     * 处理applyHeaders。
     */
    private void applyHeaders(HttpHeaders headers, String apiKey) {
        if (StringUtils.hasText(apiKey)) {
            headers.setBearerAuth(apiKey);
        }
    }

    /**
     * 处理BgeRerankRequest。
     */
    private record BgeRerankRequest(
            String model,
            String query,
            List<String> documents,
            int top_n,
            boolean return_documents
    ) {
    }

    /**
     * 处理BgeRerankResponse。
     */
    private record BgeRerankResponse(List<BgeRerankResult> results) {
    }

    private record BgeRerankResult(
            Integer index,
            @JsonProperty("relevance_score") Double relevanceScore
    ) {
    }
}
