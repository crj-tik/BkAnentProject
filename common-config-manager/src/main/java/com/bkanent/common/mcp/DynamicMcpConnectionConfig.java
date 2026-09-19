package com.bkanent.common.mcp;

import java.util.List;
import java.util.Map;

public record DynamicMcpConnectionConfig(
        String name,
        String type,
        String command,
        List<String> args,
        Map<String, String> env,
        String url) {
}
