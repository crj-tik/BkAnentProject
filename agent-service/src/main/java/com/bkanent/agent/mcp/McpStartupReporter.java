package com.bkanent.agent.mcp;

import com.bkanent.agent.mcp.model.McpServerStatus;
import com.bkanent.agent.service.McpDebugService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * McpStartupReporter 组件。
 */
@Component
public class McpStartupReporter {

    /**
     * 获取logger。
     */
    private static final Logger log = LoggerFactory.getLogger(McpStartupReporter.class);

    /**
     * 字段：mcpDebugService。
     */
    private final McpDebugService mcpDebugService;

    /**
     * 处理McpStartupReporter。
     */
    public McpStartupReporter(McpDebugService mcpDebugService) {
        this.mcpDebugService = mcpDebugService;
    }

    /**
     * 上报。
     */
    @PostConstruct
    public void report() {
        List<McpServerStatus> statuses = mcpDebugService.listServerStatuses();
        int registeredCount = mcpDebugService.listRegisteredTools().size();
        if (statuses.isEmpty()) {
            log.info("MCP startup summary: no MCP servers configured");
            return;
        }
        log.info("MCP startup summary: {} server(s), {} registered tool(s)", statuses.size(), registeredCount);
        for (McpServerStatus status : statuses) {
            if (status.available()) {
                log.info("MCP server {} available at {}, discovered {} tool(s)",
                        status.serverName(), status.endpoint(), status.discoveredToolCount());
            } else {
                log.warn("MCP server {} unavailable at {}: {}",
                        status.serverName(), status.endpoint(), status.errorMessage());
            }
        }
    }
}
