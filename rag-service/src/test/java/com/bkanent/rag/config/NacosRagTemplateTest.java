package com.bkanent.rag.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import com.bkanent.common.rag.MemoryRagRecordType;
import com.bkanent.common.rag.MemoryRagSpaceIds;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Test void memoryV2SpacesAreRegisteredWithRequiredScopeAndUniquePhysicalTargets() throws java.io.IOException {
        Map<String, Object> spaces = section(section(template(), "rag"), "spaces");
        List<String> keys = spaces.keySet().stream().filter(key -> key.startsWith("[memory.v2.")).toList();
        assertThat(keys).hasSize(20).noneMatch(key -> key.contains("compare")
                || key.contains(".contract.contract-agent") || key.contains(".settlement.settlement-agent"));
        Set<String> indices = new HashSet<>();
        Set<String> collections = new HashSet<>();
        for (String key : keys) {
            Map<String, Object> definition = section(spaces, key);
            String spaceId = (String) definition.get("space-id");
            MemoryRagRecordType type = MemoryRagRecordType.fromId((String) definition.get("memory-record-type"));
            String domain = (String) definition.get("memory-domain-id");
            String agent = (String) definition.get("memory-agent-id");
            assertThat(spaceId).isEqualTo(key.substring(1, key.length() - 1));
            assertThat(spaceId).isEqualTo(MemoryRagSpaceIds.spaceId(type, domain, agent));
            assertThat(definition.get("owner-domain")).isEqualTo("memory-service");
            assertThat(definition.get("schema-version")).isEqualTo("memory-v2.1");
            assertThat(definition.get("candidate-authorization-required")).isEqualTo(true);
            assertThat(indices.add((String) definition.get("elasticsearch-index"))).isTrue();
            Map<String, Object> vector = section(definition, "vector-schema");
            assertThat(collections.add((String) vector.get("collection"))).isTrue();

            Map<String, Object> fields = section(definition, "fields");
            List<String> required = type == MemoryRagRecordType.COLLABORATION
                    ? List.of("ownerId", "active", "recipientDomainId", "recipientAgentId", "grantId")
                    : List.of("ownerId", "active", "domainId", "agentId");
            for (String fieldName : required) {
                assertThat(section(fields, fieldName).get("required")).isEqualTo(true);
            }
            Map<String, Object> clients = section(definition, "allowed-clients");
            assertThat((List<String>) clients.get("memory-service")).contains("MEMORY_SEARCH");
        }
    }
}
