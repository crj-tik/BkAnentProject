package com.bkanent.agent.mcp;

import com.bkanent.agent.config.AgentAuthorizationProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.ai.mcp.customizer.McpSyncClientCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class AgentMcpAuthorizationConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "agent.authorization", name = "enabled", havingValue = "true")
    public AgentMcpAuthorizationSupport agentMcpAuthorizationSupport(
            AgentAuthorizationProperties authorization, McpStreamableHttpClientProperties connections) {
        return new AgentMcpAuthorizationSupport(authorization, connections);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.authorization", name = "enabled", havingValue = "true")
    public AgentMcpAuthorizationTransportCustomizer agentMcpAuthorizationTransportCustomizer(
            AgentAuthorizationProperties authorization, AgentMcpAuthorizationSupport support) {
        return new AgentMcpAuthorizationTransportCustomizer(authorization, support, Clock.systemUTC());
    }
}
