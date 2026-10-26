package com.bkanent.common.mcp.authz;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.time.Clock;

/** Optional server-side MCP proof guard; disabled unless an MCP service opts in explicitly. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration")
@ConditionalOnProperty(prefix = "mcp.authorization.server", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(McpAuthorizationServerProperties.class)
public class McpAuthorizationAutoConfiguration {

    @Bean
    public McpAuthorizationReplayGuard mcpAuthorizationReplayGuard(McpAuthorizationServerProperties properties) {
        return new McpAuthorizationReplayGuard(properties.getReplayCacheEntries());
    }

    @Bean
    public McpAuthorizationFilter mcpAuthorizationFilter(McpAuthorizationServerProperties properties,
                                                          Environment environment,
                                                          McpAuthorizationReplayGuard replayGuard) {
        String audience = environment.getProperty("spring.application.name");
        String endpoint = environment.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp");
        properties.validate(audience, endpoint);
        return new McpAuthorizationFilter(endpoint, audience, properties, replayGuard, Clock.systemUTC());
    }
}
