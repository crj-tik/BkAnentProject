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
        String publisher = read(root, "scripts/deploy/publish-nacos.py");
        String cutover = read(root, "docs/listing-comparison-cutover.md");

        // 2026-11-05 内部数据决定：外部 HTTP provider 退役，正式业务仅使用内部 legacy-local 房源。
        List<String> listingKeys = List.of("LISTING_SOURCE_PROVIDER", "LISTING_SOURCE_DATASET_ID",
                "LISTING_SOURCE_FIXTURE_BROKER_ID", "LISTING_AUTHORIZATION_ENABLED",
                "LISTING_COMPARISON_STORAGE_DIR");
        for (String key : listingKeys) {
            assertTrue(envExample.contains(key + "="), "missing .env.example value for " + key);
            assertTrue(listingApplication.contains("${" + key + ":"), "missing application placeholder for " + key);
            assertTrue(listingNacos.contains("${" + key + ":"), "missing Nacos placeholder for " + key);
            assertTrue(compose.contains(key + ": ${" + key + ":"), "missing Compose pass-through for " + key);
        }
        // Retired external HTTP provider keys must not be re-introduced in any template.
        for (String retired : List.of("LISTING_SOURCE_HTTP_BASE_URL", "LISTING_SOURCE_HTTP_BEARER_TOKEN",
                "LISTING_SOURCE_HTTP_SYSTEM", "LISTING_SOURCE_HTTP_CONFIRMED_DATA_MODE",
                "LISTING_SOURCE_HTTP_CONNECT_TIMEOUT_MS", "LISTING_SOURCE_HTTP_READ_TIMEOUT_MS")) {
            assertFalse(envExample.contains(retired), "retired key must not return to .env.example: " + retired);
            assertFalse(listingApplication.contains(retired), "retired key must not return to application.yml: " + retired);
            assertFalse(listingNacos.contains(retired), "retired key must not return to Nacos: " + retired);
            assertFalse(compose.contains(retired), "retired key must not return to Compose: " + retired);
        }
        // Cross-service owner-profile and inquiry verification secrets stay wired in every template.
        List<String> verifyKeys = List.of("LISTING_CUSTOMER_VERIFY_SIGNING_SECRET",
                "CUSTOMER_LISTING_INQUIRY_VERIFY_SECRET");
        for (String key : verifyKeys) {
            assertTrue(envExample.contains(key + "="), "missing .env.example value for " + key);
            assertTrue(compose.contains(key + ": ${" + key + ":-}"), "missing Compose pass-through for " + key);
        }

        String optionalMemory = "MEMORY_V2_AUTHORIZATION_CONTEXT_ENABLED";
        assertTrue(envExample.contains(optionalMemory + "=false"));
        assertTrue(memoryApplication.contains("${" + optionalMemory + ":false}"));
        assertTrue(memoryNacos.contains("${" + optionalMemory + ":false}"));
        assertTrue(compose.contains(optionalMemory + ": ${" + optionalMemory + ":-false}"));
        assertTrue(cutover.contains(optionalMemory));

        assertFalse(envExample.contains("COMPARE_MEMORY_EXPERIENCE_OUTCOME_PUBLISHER="));
        assertFalse(agentNacos.contains("compare-mcp-server"));
        assertFalse(compose.contains("COMPARE_MCP_ENDPOINT"));
        assertFalse(envExample.contains("COMPARE_MCP_ENDPOINT"));
        assertFalse(read(root, "agent-service/src/main/resources/application.yml").contains("COMPARE_MCP_ENDPOINT"));
        assertTrue(agentNacos.contains("baseline-capabilities: ${AGENT_AUTHORIZATION_BASELINE_CAPABILITIES:}"));
        assertFalse(agentNacos.contains("\"[a2a:listing-agent]\": listing.read"));
        assertFalse(agentNacos.contains("[a2a:compare-agent]"));
        assertTrue(gatewayNacos.contains("id: listing-master-service\n            uri: lb://listing-master-service"));
        assertTrue(gatewayNacos.contains("Path=/listings/**"));
        assertFalse(publisher.contains("\"compare-engine-service.yaml\""));
        assertFalse(compose.contains("profiles: [legacy-compare]"));
        assertFalse(compose.contains("SERVICE_ARTIFACT: compare-engine-service-1.0.0-SNAPSHOT.jar"));
        assertFalse(read(root, "pom.xml").contains("<module>compare-engine-service</module>"));
        assertFalse(envExample.contains("COMPARE_A2A_ENABLED="));
        assertFalse(envExample.contains("COMPARE_LEGACY_REST_ENABLED="));
        assertFalse(envExample.contains("COMPARE_REPORT_STORAGE_DIR="));
        assertFalse(authNacos.contains("compare-engine-service-current:"));
        assertFalse(memoryNacos.contains("compare-engine-service-current:"));
        assertTrue(cutover.contains("旧 `/compare/**` 控制器及旧响应兼容已移除"));
        assertTrue(cutover.contains("网关的 `/compare/**` 配置与部署状态"));
        assertTrue(cutover.contains("Memory 的 caller/domain/agent 映射"));
        assertTrue(cutover.contains("legacy-compare"));
        assertEquals(1, occurrences(compose, "listing-comparison-data:/app/runtime"));
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
