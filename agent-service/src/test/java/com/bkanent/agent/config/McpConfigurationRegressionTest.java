package com.bkanent.agent.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class McpConfigurationRegressionTest {

    @Test
    void keepsConfiguredMcpTransportEndpointsIndependentFromA2aMetadata() throws IOException {
        Path nacos = locateNacosDirectory();
        String agentConfig = Files.readString(nacos.resolve("agent-service.yaml"));
        String businessConfig = Files.readString(nacos.resolve("business-service.yaml"));

        assertThat(agentConfig)
                .contains("mcp:")
                .contains("type: STREAMABLE")
                .contains("/mcp");
        assertThat(businessConfig)
                .contains("mcp:")
                .contains("protocol: ${MCP_SERVER_PROTOCOL:SSE}")
                .contains("sse-endpoint: ${MCP_SERVER_SSE_ENDPOINT:/sse}")
                .contains("sse-message-endpoint: ${MCP_SERVER_SSE_MESSAGE_ENDPOINT:/mcp/message}");
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
