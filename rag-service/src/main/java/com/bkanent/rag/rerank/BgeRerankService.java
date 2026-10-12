package com.bkanent.rag.rerank;

import com.bkanent.common.rag.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.*;

/** Migrated native/DashScope protocols using only the security-owned HTTP builder. */
@Component
public class BgeRerankService {
    private final RestClient client;
    private final RerankEndpoint endpoint;
    private final RerankValidator validator = new RerankValidator();
    public BgeRerankService(@Qualifier("ragProtectedRestClientBuilder") RestClient.Builder builder, RerankEndpoint endpoint) {
        this.client = builder.build();
        this.endpoint = endpoint;
    }
    public List<RagCandidate> rerank(String query, List<RagCandidate> candidates, RerankProfile profile) {
        if (candidates.isEmpty()) return List.of();
        if (profile == null || endpoint.uri() == null || endpoint.protocol() == null || !Objects.equals(endpoint.model(), profile.modelVersion())) {
            throw new RagException(RagErrorCode.SCORING_PROFILE_MISMATCH);
        }
        List<String> documents = candidates.stream().map(RagCandidate::content).toList();
        List<RerankValidator.RankedIndex> results;
        if (endpoint.protocol() == RerankEndpoint.Protocol.NATIVE) {
            NativeResponse[] response = post(new NativeRequest(query, documents, profile.scale() == RerankProfile.Scale.LOGIT))
                    .body(NativeResponse[].class);
            results = response == null ? null : Arrays.stream(response)
                    .map(r -> r == null ? null : new RerankValidator.RankedIndex(r.index(), r.score())).toList();
        }
        else {
            DashScopeResponse response = post(new DashScopeRequest(endpoint.model(),
                    new Input(query, documents), new Parameters(documents.size(), false))).body(DashScopeResponse.class);
            results = response == null || response.output() == null || response.output().results() == null ? null
                    : response.output().results().stream().map(r -> r == null ? null
                    : new RerankValidator.RankedIndex(r.index(), r.relevanceScore())).toList();
        }
        return validator.apply(candidates, results, profile);
    }
    private RestClient.ResponseSpec post(Object request) {
        return client.post().uri(endpoint.uri()).contentType(MediaType.APPLICATION_JSON)
                .headers(h -> { if (endpoint.apiKey() != null && !endpoint.apiKey().isBlank()) h.setBearerAuth(endpoint.apiKey()); })
                .body(request).retrieve().onStatus(status -> status.value() == 401 || status.value() == 403,
                        (sent, response) -> { throw new RagException(response.getStatusCode().value() == 401
                                ? RagErrorCode.AUTHENTICATION_FAILED : RagErrorCode.FORBIDDEN); });
    }
    public record NativeRequest(String query, List<String> texts, boolean raw_scores) { }
    public record NativeResponse(Integer index, Double score) { }
    public record DashScopeRequest(String model, Input input, Parameters parameters) { }
    public record Input(String query, List<String> documents) { }
    public record Parameters(int top_n, boolean return_documents) { }
    public record DashScopeResponse(Output output) { }
    public record Output(List<DashScopeResult> results) { }
    public record DashScopeResult(Integer index, @JsonProperty("relevance_score") Double relevanceScore) { }
}
