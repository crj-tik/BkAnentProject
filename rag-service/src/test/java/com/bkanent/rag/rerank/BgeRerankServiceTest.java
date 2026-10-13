package com.bkanent.rag.rerank;

import com.bkanent.common.rag.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.util.List;
import static com.bkanent.rag.retrieval.RetrievalTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class BgeRerankServiceTest {
    private static final String GOVERNED_LOGIT_BODY = "{\"model\":{\"alias\":\"bge-1\",\"model_name\":\"BAAI/bge-reranker-v2-m3\","
            + "\"revision\":\"rev-1\",\"scoring_version\":\"scoring-1\",\"artifact_digest\":\"digest-1\"},"
            + "\"score_semantics\":\"LOGIT\",\"results\":[{\"index\":0,\"score\":1.1},{\"index\":1,\"score\":2.2}],"
            + "\"usage\":{\"pair_count\":2,\"cache_hits\":0,\"input_pair_tokens\":10,\"inference_pair_tokens\":10,\"estimated_pair_tokens\":10},"
            + "\"truncation\":{\"enabled\":false,\"strategy\":\"only_second_right\",\"effective_max_length\":1024,\"truncated_indices\":[],"
            + "\"original_pair_tokens\":[5,5],\"effective_pair_tokens\":[5,5]},"
            + "\"timings\":{\"total_ms\":1.0,\"queue_ms\":0.0,\"inference_ms\":1.0},\"request_id\":\"r-1\"}";
    private static RerankProfile logitProfile() {
        return new RerankProfile("governed", "bge-1", "scoring-1", RerankProfile.Scale.LOGIT);
    }
    private static BgeRerankService governed(RestClient.Builder builder) {
        return new BgeRerankService(builder, new RerankEndpoint(URI.create("https://rerank.example/api/v1/rerank"),
                null, "bge-1", RerankEndpoint.Protocol.GOVERNED, "rev-1", "digest-1"));
    }
    @Test void nativeUsesOriginalProtocolAndExplicitScaleWithoutNetwork() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://rerank.example/rerank")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"query\":\"q\",\"texts\":[\"text a\",\"text b\"],\"raw_scores\":false}"))
                .andRespond(withSuccess("[{\"index\":1,\"score\":0.8},{\"index\":0,\"score\":0.2}]", MediaType.APPLICATION_JSON));
        BgeRerankService service = new BgeRerankService(builder,
                new RerankEndpoint(URI.create("https://rerank.example/rerank"), null, "bge-1", RerankEndpoint.Protocol.NATIVE));
        var result = service.rerank("q", List.of(hit("a", 1, RagRecallMode.KEYWORD).candidate(), hit("b", 2, RagRecallMode.VECTOR).candidate()), space().rerankProfile());
        assertEquals("b", result.get(0).documentId());
        assertEquals(RagScoreKind.SEMANTIC_NORMALIZED, result.get(0).scoreKind());
        server.verify();
    }
    @Test void dashScopeRequestsAllCandidatesAndRejectsMissingIndices() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://rerank.example/rerank"))
                .andExpect(jsonPath("$.parameters.top_n").value(2))
                .andExpect(jsonPath("$.parameters.return_documents").value(false))
                .andRespond(withSuccess("{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.8}]}}", MediaType.APPLICATION_JSON));
        BgeRerankService service = new BgeRerankService(builder,
                new RerankEndpoint(URI.create("https://rerank.example/rerank"), null, "bge-1", RerankEndpoint.Protocol.DASHSCOPE));
        assertEquals(RagErrorCode.RERANK_PROTOCOL_ERROR, assertThrows(RagException.class, () -> service.rerank("q",
                List.of(hit("a", 1, RagRecallMode.KEYWORD).candidate(), hit("b", 2, RagRecallMode.VECTOR).candidate()), space().rerankProfile())).code());
        server.verify();
    }
    @Test void governedRequestsRawLogitsAndPinsModelIdentity() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://rerank.example/api/v1/rerank")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"model\":\"bge-1\",\"query\":\"q\",\"passages\":[\"text a\",\"text b\"],\"raw_scores\":true,\"truncate\":false}"))
                .andRespond(withSuccess(GOVERNED_LOGIT_BODY, MediaType.APPLICATION_JSON));
        var result = governed(builder).rerank("q", List.of(hit("a", 1, RagRecallMode.KEYWORD).candidate(),
                hit("b", 2, RagRecallMode.VECTOR).candidate()), logitProfile());
        assertEquals("b", result.get(0).documentId());
        assertEquals(2.2, result.get(0).rawRerankScore(), 1e-12);
        assertEquals(1.0 / (1.0 + Math.exp(-2.2)), result.get(0).relevanceScore(), 1e-12);
        assertEquals(RagScoreKind.SEMANTIC_NORMALIZED, result.get(0).scoreKind());
        assertEquals("scoring-1", result.get(0).scoringVersion());
        server.verify();
    }
    @Test void governedBoundedProfileRequestsSigmoidRelevanceAndPassesItThrough() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://rerank.example/api/v1/rerank"))
                .andExpect(content().json("{\"model\":\"bge-1\",\"raw_scores\":false,\"truncate\":false}"))
                .andRespond(withSuccess("{\"model\":{\"alias\":\"bge-1\",\"revision\":\"rev-1\",\"scoring_version\":\"scoring-1\","
                        + "\"artifact_digest\":\"digest-1\"},\"score_semantics\":\"SIGMOID_RELEVANCE\","
                        + "\"results\":[{\"index\":1,\"score\":0.9},{\"index\":0,\"score\":0.1}],"
                        + "\"truncation\":{\"enabled\":false}}", MediaType.APPLICATION_JSON));
        var profile = new RerankProfile("governed", "bge-1", "scoring-1", RerankProfile.Scale.BOUNDED_0_1);
        var result = governed(builder).rerank("q", List.of(hit("a", 1, RagRecallMode.KEYWORD).candidate(),
                hit("b", 2, RagRecallMode.VECTOR).candidate()), profile);
        assertEquals("b", result.get(0).documentId());
        assertEquals(0.9, result.get(0).relevanceScore(), 1e-12);
        server.verify();
    }
    @Test void governedRejectsIdentityOrSemanticsDriftAsProfileMismatch() {
        for (String body : List.of(GOVERNED_LOGIT_BODY.replace("\"rev-1\"", "\"rev-2\""),
                GOVERNED_LOGIT_BODY.replace("\"LOGIT\"", "\"SIGMOID_RELEVANCE\""))) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(once(), requestTo("https://rerank.example/api/v1/rerank"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertEquals(RagErrorCode.SCORING_PROFILE_MISMATCH, assertThrows(RagException.class, () -> governed(builder).rerank("q",
                    List.of(hit("a", 1, RagRecallMode.KEYWORD).candidate()), logitProfile())).code());
            server.verify();
        }
    }
    @Test void governedRejectsUnexpectedTruncationAsProtocolError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://rerank.example/api/v1/rerank"))
                .andRespond(withSuccess(GOVERNED_LOGIT_BODY.replace("\"enabled\":false", "\"enabled\":true"), MediaType.APPLICATION_JSON));
        assertEquals(RagErrorCode.RERANK_PROTOCOL_ERROR, assertThrows(RagException.class, () -> governed(builder).rerank("q",
                List.of(hit("a", 1, RagRecallMode.KEYWORD).candidate()), logitProfile())).code());
        server.verify();
    }
    @Test void governed400And413AreNonRetryableArgumentFailures() {
        for (org.springframework.http.HttpStatus status : List.of(org.springframework.http.HttpStatus.BAD_REQUEST,
                org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE)) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(once(), requestTo("https://rerank.example/api/v1/rerank"))
                    .andRespond(withStatus(status).body("{\"error\":{\"code\":\"INVALID_REQUEST\",\"message\":\"rejected\"}}"));
            assertEquals(RagErrorCode.INVALID_ARGUMENT, assertThrows(RagException.class, () -> governed(builder).rerank("q",
                    List.of(hit("a", 1, RagRecallMode.KEYWORD).candidate()), logitProfile())).code());
            server.verify();
        }
    }
    @Test void governedRequiresPinnedIdentityWithoutNetwork() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BgeRerankService service = new BgeRerankService(builder, new RerankEndpoint(URI.create("https://rerank.example/api/v1/rerank"),
                null, "bge-1", RerankEndpoint.Protocol.GOVERNED, null, null));
        assertEquals(RagErrorCode.SCORING_PROFILE_MISMATCH, assertThrows(RagException.class, () -> service.rerank("q",
                List.of(hit("a", 1, RagRecallMode.KEYWORD).candidate()), logitProfile())).code());
        server.verify();
    }
    @Test void actualProvider401And403AreNotRetryableDependencyFailures() {
        for (org.springframework.http.HttpStatus status : List.of(org.springframework.http.HttpStatus.UNAUTHORIZED,
                org.springframework.http.HttpStatus.FORBIDDEN)) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://rerank.example/rerank")).andRespond(withStatus(status));
            BgeRerankService service = new BgeRerankService(builder,
                    new RerankEndpoint(URI.create("https://rerank.example/rerank"), null, "bge-1", RerankEndpoint.Protocol.NATIVE));
            assertEquals(status.value() == 401 ? RagErrorCode.AUTHENTICATION_FAILED : RagErrorCode.FORBIDDEN,
                    assertThrows(RagException.class, () -> service.rerank("q",
                            List.of(hit("a", 1, RagRecallMode.KEYWORD).candidate()), space().rerankProfile())).code());
            server.verify();
        }
    }
    @Test void emptyNeverCallsProvider() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BgeRerankService service = new BgeRerankService(builder, new RerankEndpoint(null, null, null, null));
        assertTrue(service.rerank("q", List.of(), space().rerankProfile()).isEmpty());
        server.verify();
    }
}
