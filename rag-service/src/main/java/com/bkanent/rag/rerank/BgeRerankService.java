package com.bkanent.rag.rerank;

import com.bkanent.common.rag.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.*;

/** Migrated native/DashScope/governed protocols using only the security-owned HTTP builder. */
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
        else if (endpoint.protocol() == RerankEndpoint.Protocol.GOVERNED) {
            results = governed(query, documents, profile);
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

    /**
     * Governed /api/v1/rerank: the deployed model identity (alias, revision, scoring version,
     * artifact digest) is pinned in managed configuration and re-verified on every response;
     * a same-alias model swap must fail closed instead of silently changing score semantics.
     */
    private List<RerankValidator.RankedIndex> governed(String query, List<String> documents, RerankProfile profile) {
        if (blank(endpoint.expectedRevision()) || blank(endpoint.expectedArtifactDigest())) {
            throw new RagException(RagErrorCode.SCORING_PROFILE_MISMATCH);
        }
        GovernedResponse response = post(new GovernedRequest(endpoint.model(), query, documents,
                profile.scale() == RerankProfile.Scale.LOGIT, false)).body(GovernedResponse.class);
        if (response == null || response.model() == null || response.truncation() == null || response.results() == null) {
            throw new RagException(RagErrorCode.RERANK_PROTOCOL_ERROR);
        }
        String expectedSemantics = profile.scale() == RerankProfile.Scale.LOGIT ? "LOGIT" : "SIGMOID_RELEVANCE";
        if (!Objects.equals(response.model().alias(), endpoint.model())
                || !Objects.equals(response.model().revision(), endpoint.expectedRevision())
                || !Objects.equals(response.model().scoringVersion(), profile.scoringVersion())
                || !Objects.equals(response.model().artifactDigest(), endpoint.expectedArtifactDigest())
                || !Objects.equals(response.scoreSemantics(), expectedSemantics)) {
            throw new RagException(RagErrorCode.SCORING_PROFILE_MISMATCH);
        }
        // truncate=false was requested; enabled truncation means the provider silently shortened texts.
        if (response.truncation().enabled()) throw new RagException(RagErrorCode.RERANK_PROTOCOL_ERROR);
        return response.results().stream().map(r -> r == null ? null
                : new RerankValidator.RankedIndex(r.index(), r.score())).toList();
    }

    private RestClient.ResponseSpec post(Object request) {
        return client.post().uri(endpoint.uri()).contentType(MediaType.APPLICATION_JSON)
                .headers(h -> { if (endpoint.apiKey() != null && !endpoint.apiKey().isBlank()) h.setBearerAuth(endpoint.apiKey()); })
                .body(request).retrieve().onStatus(status -> status.value() == 401 || status.value() == 403
                        || status.value() == 400 || status.value() == 413,
                        (sent, response) -> { throw clientError(response.getStatusCode().value()); });
    }
    private static RagException clientError(int status) {
        if (status == 401) return new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        if (status == 403) return new RagException(RagErrorCode.FORBIDDEN);
        // Rejected parameters or oversized payloads are configuration drift, not transient failures; no retry.
        return new RagException(RagErrorCode.INVALID_ARGUMENT, "rerankRequest");
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record NativeRequest(String query, List<String> texts, boolean raw_scores) { }
    public record NativeResponse(Integer index, Double score) { }
    public record DashScopeRequest(String model, Input input, Parameters parameters) { }
    public record Input(String query, List<String> documents) { }
    public record Parameters(int top_n, boolean return_documents) { }
    public record DashScopeResponse(Output output) { }
    public record Output(List<DashScopeResult> results) { }
    public record DashScopeResult(Integer index, @JsonProperty("relevance_score") Double relevanceScore) { }

    public record GovernedRequest(String model, String query, List<String> passages, boolean raw_scores, boolean truncate) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GovernedResponse(GovernedModel model, @JsonProperty("score_semantics") String scoreSemantics,
            List<NativeResponse> results, Truncation truncation) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GovernedModel(String alias, @JsonProperty("model_name") String modelName, String revision,
            @JsonProperty("scoring_version") String scoringVersion, @JsonProperty("artifact_digest") String artifactDigest) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Truncation(boolean enabled) { }
}
