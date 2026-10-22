package com.bkanent.rag.security.http;

import com.bkanent.common.rag.RagCandidate;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagRecallMode;
import com.bkanent.common.rag.RagScoreKind;
import com.bkanent.rag.rerank.BgeRerankService;
import com.bkanent.rag.rerank.RerankEndpoint;
import com.bkanent.rag.rerank.RerankProfile;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/** Live, read-only wire probe for the production RAG rerank client with synthetic passages only. */
public final class RagRerankClientProbe {
    private static final String HOST = "bkanent-reranker-dev";
    private static final String DEFAULT_ENDPOINT = "http://bkanent-reranker-dev:18111/api/v1/rerank";
    private static final String DEFAULT_MODEL = "bge-reranker-v2-m3-v1";
    private static final String DEFAULT_REVISION = "324cc40576b08b305b9c65a867c26c173a477ae2";
    private static final String DEFAULT_ARTIFACT_DIGEST = "76cd4a977b9001bf8e28e41bac6ce53b2d334d0c567f6928a9b88655bafc833f";
    private static final String SCORING_VERSION = "12ab2575eb91fb134ec2d616b872f37578457fe88258ce2906609bcdc63db88b";

    private RagRerankClientProbe() { }

    public static void main(String[] args) {
        if (args.length != 0) {
            System.err.println("probe=rag-rerank-client result=FAIL code=INVALID_ARGUMENT");
            System.exit(2);
        }
        try {
            run();
        } catch (RagException failure) {
            System.err.println("probe=rag-rerank-client result=FAIL code=" + failure.code());
            System.exit(1);
        } catch (Throwable failure) {
            // Never print exception messages, URLs, headers, request bodies, or provider response bodies.
            System.err.println("probe=rag-rerank-client result=FAIL errorClass=" + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        URI uri = URI.create(value("RAG_RERANK_ENDPOINT", DEFAULT_ENDPOINT));
        String model = value("RAG_RERANK_MODEL", DEFAULT_MODEL);
        String protocol = value("RAG_RERANK_PROTOCOL", "governed");
        String revision = value("RAG_RERANK_EXPECTED_REVISION", DEFAULT_REVISION);
        String digest = value("RAG_RERANK_EXPECTED_ARTIFACT_DIGEST", DEFAULT_ARTIFACT_DIGEST);
        String apiKey = System.getenv("RAG_RERANK_API_KEY");
        if (!"http".equalsIgnoreCase(uri.getScheme()) || !HOST.equalsIgnoreCase(uri.getHost())
                || uri.getPort() != 18111 || !"/api/v1/rerank".equals(uri.getPath())
                || !"governed".equalsIgnoreCase(protocol) || model.isBlank()
                || revision.isBlank() || digest.isBlank()) {
            throw new RagException(com.bkanent.common.rag.RagErrorCode.SCORING_PROFILE_MISMATCH);
        }

        RagHttpPolicyConfiguration policyConfig = new RagHttpPolicyConfiguration("MANAGED_INTERNAL",
                List.of(HOST), Map.of(HOST, List.of("10.253.240.0/24")));
        RagEndpointPolicy policy = RagEndpointPolicy.configured(() -> policyConfig);
        RagHttpTransportConfiguration transport = new RagHttpTransportConfiguration();
        ExecutorService dnsExecutor = transport.ragDnsExecutor();
        ScheduledExecutorService deadlineScheduler = transport.ragHttpDeadlineScheduler();
        PoolingHttpClientConnectionManager connectionManager = null;
        CloseableHttpClient httpClient = null;
        try {
            RagPublicDnsResolver resolver = transport.ragPublicDnsResolver(policy, dnsExecutor);
            connectionManager = transport.ragHttpConnectionManager(policy, resolver);
            httpClient = transport.ragProtectedHttpClient(policy, connectionManager);
            RestClient.Builder protectedBuilder = transport.ragProtectedRestClientBuilder(
                    httpClient, policy, deadlineScheduler);

            RerankEndpoint endpoint = new RerankEndpoint(uri,
                    apiKey == null || apiKey.isBlank() ? null : apiKey, model,
                    RerankEndpoint.Protocol.GOVERNED, revision, digest);
            RerankProfile profile = new RerankProfile("governed", model, SCORING_VERSION,
                    RerankProfile.Scale.LOGIT);
            List<RagCandidate> candidates = List.of(
                    candidate("synthetic-paris", "Paris is the capital city of France."),
                    candidate("synthetic-tokyo", "Tokyo is the capital city of Japan."));

            long started = System.nanoTime();
            List<RagCandidate> ranked = new BgeRerankService(protectedBuilder, endpoint)
                    .rerank("Which city is the capital of France?", candidates, profile);
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
            if (ranked.size() != 2 || !"synthetic-paris".equals(ranked.get(0).documentId())
                    || ranked.get(0).relevanceScore() == null || ranked.get(1).relevanceScore() == null
                    || ranked.get(0).relevanceScore() <= ranked.get(1).relevanceScore()
                    || ranked.stream().anyMatch(c -> c.scoreKind() != RagScoreKind.SEMANTIC_NORMALIZED
                    || !SCORING_VERSION.equals(c.scoringVersion())
                    || c.rawRerankScore() == null || !Double.isFinite(c.rawRerankScore())
                    || c.relevanceScore() < 0 || c.relevanceScore() > 1)) {
                throw new RagException(com.bkanent.common.rag.RagErrorCode.RERANK_PROTOCOL_ERROR);
            }
            System.out.printf("probe=rag-rerank-client result=PASS protocol=GOVERNED candidateCount=%d "
                    + "ranking=PARIS_FIRST modelPin=PASS scoreSemantics=LOGIT normalizedScores=PASS "
                    + "scoringVersion=%s elapsedMs=%d%n", ranked.size(), SCORING_VERSION, elapsedMillis);
        } finally {
            if (httpClient != null) httpClient.close();
            if (connectionManager != null) connectionManager.close();
            deadlineScheduler.shutdownNow();
            dnsExecutor.shutdownNow();
        }
    }

    private static RagCandidate candidate(String id, String content) {
        return new RagCandidate(id, "synthetic-rerank-probe", id, content, 1, "probe-v1",
                List.of(RagRecallMode.KEYWORD), 1.0, null, 0.5, null, null, null, null, Map.of());
    }

    private static String value(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
