package com.bkanent.agent.milvus.listing;

import com.bkanent.agent.config.ListingRagProperties;
import com.bkanent.common.model.ListingDTO;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BgeRerankService 两种 rerank 协议（DashScope 云端 / 本地 bge-reranker native）的解析回归。
 */
class BgeRerankServiceTest {

    private MockWebServer server;
    private ListingRagProperties properties;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        properties = new ListingRagProperties();
        properties.setRerankEndpoint(server.url("/rerank").toString());
        properties.setRerankApiKey("test-key");
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    @Test
    void nativeProtocolSendsQueryTextsAndParsesPlainArray() throws Exception {
        properties.setRerankProtocol(ListingRagProperties.RerankProtocol.NATIVE);
        properties.setRerankModel("bge-reranker-v2-m3-v1");
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("[{\"index\":1,\"score\":0.9},{\"index\":0,\"score\":0.1}]"));

        List<ListingRecallCandidate> reranked = new BgeRerankService(properties)
                .rerank("两居室", List.of(candidate(101L, "浦东两居"), candidate(102L, "索引优化")), 2);

        var recorded = server.takeRequest();
        assertThat(recorded.getBody().readUtf8())
                .contains("\"query\":\"两居室\"")
                .contains("\"texts\":[")
                .doesNotContain("\"input\"")
                .doesNotContain("dashscope");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-key");
        assertThat(reranked).extracting(c -> c.getListing().id()).containsExactly(102L, 101L);
        assertThat(reranked.get(0).getRerankScore()).isEqualTo(0.9);
    }

    @Test
    void dashScopeProtocolKeepsOriginalEnvelope() throws Exception {
        properties.setRerankProtocol(ListingRagProperties.RerankProtocol.DASHSCOPE);
        properties.setRerankModel("qwen3-rerank");
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.8}]}}"));

        List<ListingRecallCandidate> reranked = new BgeRerankService(properties)
                .rerank("两居室", List.of(candidate(101L, "浦东两居")), 1);

        var recorded = server.takeRequest();
        assertThat(recorded.getBody().readUtf8())
                .contains("\"input\":{\"query\":\"两居室\"")
                .contains("\"documents\":[")
                .contains("\"model\":\"qwen3-rerank\"");
        assertThat(reranked).extracting(c -> c.getListing().id()).containsExactly(101L);
        assertThat(reranked.get(0).getRerankScore()).isEqualTo(0.8);
    }

    @Test
    void emptyCandidatesReturnEmptyWithoutHttpCall() {
        assertThat(new BgeRerankService(properties).rerank("q", List.of(), 5)).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void nativeInvalidIndexEntriesAreIgnored() {
        properties.setRerankProtocol(ListingRagProperties.RerankProtocol.NATIVE);
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("[{\"index\":5,\"score\":0.9},{\"index\":0,\"score\":0.2}]"));

        List<ListingRecallCandidate> reranked = new BgeRerankService(properties)
                .rerank("两居室", List.of(candidate(101L, "浦东两居")), 2);

        assertThat(reranked).extracting(c -> c.getListing().id()).containsExactly(101L);
    }

    private ListingRecallCandidate candidate(long id, String content) {
        ListingDTO listing = new ListingDTO(id, "title-" + id, "浦东", "两居室", BigDecimal.valueOf(89),
                BigDecimal.valueOf(280), "ACTIVE", null, null, null, null, null);
        return new ListingRecallCandidate(listing, content);
    }
}
