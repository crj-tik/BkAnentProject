package com.bkanent.agent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;

/**
 * NamedMcpSyncClient 客户端。
 */
public record NamedMcpSyncClient(
        String serverName,
        String endpoint,
        McpSyncClient client
) {
}
