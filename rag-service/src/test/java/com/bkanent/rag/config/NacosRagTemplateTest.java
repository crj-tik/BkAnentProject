package com.bkanent.rag.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the checked-in Nacos deployment template: the governed rerank profile anchor must stay
 * syntactically valid and reach all three spaces, and the reranker host needs its own CIDR entry.
 */
class NacosRagTemplateTest {
    private static final Path TEMPLATE = Path.of("..", "nacos", "rag-service.yaml");

    private static Map<String, Object> template() throws java.io.IOException {
        assumeTrue(Files.exists(TEMPLATE), "nacos template not present in this checkout");
        return new Yaml().load(Files.readString(TEMPLATE));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String key) {
        return (Map<String, Object>) root.get(key);
    }

    @Test void governedRerankProfileReachesAllRegisteredSpaces() throws java.io.IOException {
        Map<String, Object> spaces = section(section(template(), "rag"), "spaces");
        for (String spaceId : List.of("[listing.knowledge]", "[memory.preference]", "[memory.constraint]")) {
            Map<String, Object> profile = section(section(spaces, spaceId), "rerank-profile");
            assertThat(profile).isNotNull();
            assertThat(profile.get("provider")).isEqualTo("governed");
            assertThat(profile.get("model-version")).isEqualTo("bge-reranker-v2-m3-v1");
            assertThat((String) profile.get("scoring-version")).matches("[0-9a-f]{64}");
            assertThat(profile.get("scale")).isEqualTo("LOGIT");
        }
    }

    @Test void rerankerHostHasOwnManagedCidrAndIdentityPins() throws java.io.IOException {
        Map<String, Object> rag = section(template(), "rag");
        Map<String, Object> rerank = section(rag, "rerank");
        assertThat(rerank.get("protocol")).isEqualTo("${RAG_RERANK_PROTOCOL:governed}");
        assertThat(((String) rerank.get("expected-revision"))).contains("324cc405");
        assertThat(((String) rerank.get("expected-artifact-digest"))).contains("76cd4a9");
        Map<String, Object> http = section(rag, "http");
        assertThat((List<String>) http.get("allowed-hosts")).contains("bkanent-reranker-dev");
        assertThat((List<String>) ((Map<String, Object>) http.get("internal-networks")).get("bkanent-reranker-dev"))
                .containsExactly("10.253.240.0/24");
    }
}
