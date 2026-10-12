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
