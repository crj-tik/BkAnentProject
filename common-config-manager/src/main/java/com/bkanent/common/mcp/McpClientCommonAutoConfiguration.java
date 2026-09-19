package com.bkanent.common.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(McpSyncClient.class)
public class McpClientCommonAutoConfiguration {

    @Bean
    public DynamicMcpClientManager dynamicMcpClientManager() {
        return new DynamicMcpClientManager();
    }
}
