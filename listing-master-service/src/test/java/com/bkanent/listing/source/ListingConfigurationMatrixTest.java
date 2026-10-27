package com.bkanent.listing.source;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ListingConfigurationMatrixTest {
    @Test
    void listingMemoryCompareAndGatewaySettingsStaySynchronizedAcrossTemplates() throws IOException {
        Path root = projectRoot();
        String envExample = read(root, ".env.example");
        String listingApplication = read(root, "listing-master-service/src/main/resources/application.yml");
        String listingNacos = read(root, "nacos/listing-master-service.yaml");
        String compose = read(root, "docker-compose.yml");
        String agentNacos = read(root, "nacos/agent-service.yaml");
        String gatewayNacos = read(root, "nacos/gateway.yaml");
        String memoryApplication = read(root, "memory-service/src/main/resources/application.yml");
        String memoryNacos = read(root, "nacos/memory-service.yaml");
        String authNacos = read(root, "nacos/auth-service.yaml");
        String compareNacos = read(root, "nacos/compare-engine-service.yaml");
        String compareApplication = read(root, "compare-engine-service/src/main/resources/application.yml");
        String publisher = read(root, "scripts/deploy/publish-nacos.py");
        String cutover = read(root, "docs/listing-comparison-cutover.md");

        List<String> listingKeys = List.of("LISTING_SOURCE_PROVIDER", "LISTING_SOURCE_DATASET_ID",
                "LISTING_SOURCE_FIXTURE_BROKER_ID", "LISTING_SOURCE_HTTP_BASE_URL",
                "LISTING_SOURCE_HTTP_BEARER_TOKEN", "LISTING_SOURCE_HTTP_SYSTEM",
                "LISTING_SOURCE_HTTP_CONFIRMED_DATA_MODE", "LISTING_SOURCE_HTTP_CONNECT_TIMEOUT_MS",
                "LISTING_SOURCE_HTTP_READ_TIMEOUT_MS", "LISTING_AUTHORIZATION_ENABLED",
                "LISTING_COMPARISON_STORAGE_DIR");
        for (String key : listingKeys) {
            assertTrue(envExample.contains(key + "="), "missing .env.example value for " + key);
            assertTrue(listingApplication.contains("${" + key + ":"), "missing application placeholder for " + key);
            assertTrue(listingNacos.contains("${" + key + ":"), "missing Nacos placeholder for " + key);
            assertTrue(compose.contains(key + ": ${" + key + ":"), "missing Compose pass-through for " + key);
            assertTrue(cutover.contains(key), "missing config matrix row for " + key);
        }

        String optionalMemory = "MEMORY_V2_AUTHORIZATION_CONTEXT_ENABLED";
        assertTrue(envExample.contains(optionalMemory + "=false"));
        assertTrue(memoryApplication.contains("${" + optionalMemory + ":false}"));
        assertTrue(memoryNacos.contains("${" + optionalMemory + ":false}"));
        assertTrue(compose.contains(optionalMemory + ": ${" + optionalMemory + ":-false}"));
        assertTrue(cutover.contains(optionalMemory));

        for (String key : List.of("COMPARE_A2A_ENABLED", "COMPARE_MCP_ENABLED")) {
            assertTrue(envExample.contains(key + "=false"));
            assertTrue(compose.contains(key + ": ${" + key + ":-false}"));
            assertTrue(compareNacos.contains("${" + key + ":false}"));
        }
        for (String key : List.of("COMPARE_LEGACY_REST_ENABLED", "COMPARE_LEGACY_RPC_ENABLED")) {
            assertTrue(envExample.contains(key + "=false"));
            assertTrue(compose.contains(key + ": ${" + key + ":-false}"));
            assertTrue(compareApplication.contains("${" + key + ":false}"));
            assertTrue(compareNacos.contains("${" + key + ":false}"));
            assertTrue(cutover.contains(key));
        }
        for (String key : List.of("COMPARE_AGENT_MODEL", "COMPARE_AGENT_TEMPERATURE", "COMPARE_AGENT_MAX_TOKENS",
                "COMPARE_AI_CONCLUSION_ENABLED", "COMPARE_REPORT_STORAGE_DIR")) {
            assertTrue(envExample.contains(key + "="), "missing rollback value for " + key);
            assertTrue(compose.contains(key + ": ${" + key + ":-"), "missing legacy profile pass-through for " + key);
            assertTrue(compareNacos.contains("${" + key + ":"), "missing Compare Nacos placeholder for " + key);
            assertTrue(cutover.contains(key), "missing rollback config matrix row for " + key);
        }
        assertTrue(envExample.contains("COMPARE_MEMORY_V2_COMPRESSION_ENABLED=false"));
        assertTrue(compose.contains("COMPARE_MEMORY_V2_COMPRESSION_ENABLED: ${COMPARE_MEMORY_V2_COMPRESSION_ENABLED:-false}"));
        assertTrue(compareApplication.contains("${COMPARE_MEMORY_V2_COMPRESSION_ENABLED:false}"));
        assertTrue(cutover.contains("COMPARE_MEMORY_V2_COMPRESSION_ENABLED"));
        assertTrue(envExample.contains("COMPARE_MEMORY_EXPERIENCE_OUTCOME_PUBLISHER=false"));
        assertTrue(compose.contains("COMPARE_MEMORY_EXPERIENCE_OUTCOME_PUBLISHER: ${COMPARE_MEMORY_EXPERIENCE_OUTCOME_PUBLISHER:-false}"));
        assertTrue(memoryNacos.contains("${COMPARE_MEMORY_EXPERIENCE_OUTCOME_PUBLISHER:false}"));
        assertTrue(cutover.contains("COMPARE_MEMORY_EXPERIENCE_OUTCOME_PUBLISHER"));
        assertFalse(agentNacos.contains("compare-mcp-server"));
        assertFalse(compose.contains("COMPARE_MCP_ENDPOINT"));
        assertFalse(envExample.contains("COMPARE_MCP_ENDPOINT"));
        assertFalse(read(root, "agent-service/src/main/resources/application.yml").contains("COMPARE_MCP_ENDPOINT"));
        assertTrue(agentNacos.contains("\"[a2a:listing-agent]\": listing.read"));
        assertFalse(agentNacos.contains("[a2a:compare-agent]"));
        assertTrue(gatewayNacos.contains("id: listing-comparison-compatibility\n            uri: lb://listing-master-service"));
        assertTrue(gatewayNacos.contains("Path=/compare/**"));
        assertTrue(publisher.contains("\"compare-engine-service.yaml\""));
        assertTrue(compose.contains("profiles: [legacy-compare]"));
        assertTrue(compose.contains(".secrets/service-identities/compare-engine-service/private-key.pem"));
        assertTrue(compareApplication.contains("compare-engine-service-current"));
        assertTrue(compareNacos.contains("name: compare-agent"));
        assertTrue(authNacos.contains("compare-engine-service-current:\n        caller-service: compare-engine-service"));
        assertTrue(authNacos.contains("${COMPARE_ENGINE_SERVICE_IDENTITY_PUBLIC_KEY:}"));
        assertTrue(memoryNacos.contains("compare-engine-service-current: ${COMPARE_ENGINE_SERVICE_IDENTITY_PUBLIC_KEY:}"));
        assertTrue(memoryNacos.contains("compare: [compare-agent]"));
        assertTrue(cutover.contains("原 `taskId`"));
        assertTrue(cutover.contains("Memory caller/domain/agent"));
        assertTrue(cutover.contains("legacy-compare"));
        assertEquals(2, occurrences(compose, "compare-report-data:/app/runtime"));
        assertTrue(compose.contains(".secrets/service-identities/public-keys.env"));
    }

    private Path projectRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml")) && Files.isDirectory(current.resolve("openspec"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Project root was not found from the test working directory");
    }

    private String read(Path root, String relative) throws IOException {
        return Files.readString(root.resolve(relative)).replace("\r\n", "\n");
    }

    private long occurrences(String text, String value) {
        return text.split(java.util.regex.Pattern.quote(value), -1).length - 1;
    }
}
