package com.bkanent.agent.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpConfigurationRegressionTest {

    @Test
    void keepsConfiguredMcpTransportEndpointsIndependentFromA2aMetadata() throws IOException {
        Path nacos = locateNacosDirectory();
        String agentConfig = Files.readString(nacos.resolve("agent-service.yaml"));

        assertThat(agentConfig)
                .contains("mcp:")
                .contains("type: STREAMABLE")
                .contains("/mcp");

        Map<String, String> serverConfigs = Map.of(
                "business-mcp-server", "business-service.yaml",
                "compare-mcp-server", "compare-engine-service.yaml",
                "contract-mcp-server", "contract-service.yaml",
                "marketing-mcp-server", "marketing-content-service.yaml",
                "media-mcp-server", "media-worker-service.yaml",
                "notification-mcp-server", "notification-service.yaml",
                "settlement-mcp-server", "settlement-service.yaml"
        );
        serverConfigs.forEach((serverName, fileName) -> {
            assertThat(agentConfig)
                    .as("Supervisor MCP client connection for %s", serverName)
                    .contains(serverName)
                    .contains("type: STREAMABLE");
            try {
                String serverConfig = Files.readString(nacos.resolve(fileName));
                assertThat(serverConfig)
                        .as("MCP server configuration for %s", serverName)
                        .contains("name: " + serverName)
                        .contains("protocol: ${MCP_SERVER_PROTOCOL:STREAMABLE}")
                        .contains("mcp-endpoint: ${MCP_SERVER_MCP_ENDPOINT:/mcp}")
                        .doesNotContain("sse-endpoint:")
                        .doesNotContain("sse-message-endpoint:");
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to read MCP configuration " + fileName, exception);
            }
        });
    }

    private Path locateNacosDirectory() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("nacos");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("nacos configuration directory not found");
    }
}
