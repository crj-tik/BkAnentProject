package com.bkanent.agent.mcp;

/**
 * AgentMcpNames 组件。
 */
public final class AgentMcpNames {

    private static final java.util.Map<String, String> FIRST_PARTY_AUDIENCES = java.util.Map.of(
            "business-mcp-server", "business-service",
            "marketing-mcp-server", "marketing-content-service",
            "notification-mcp-server", "notification-service",
            "media-mcp-server", "media-worker-service",
            "interview-mcp-server", "interview-service");

    /**
     * 字段：BUSINESS_SERVER。
     */
    public static final String BUSINESS_SERVER = "business-mcp-server";
    /**
     * 字段：MARKETING_SERVER。
     */
    public static final String MARKETING_SERVER = "marketing-mcp-server";

    /**
     * 字段：QUERY_MONTHLY_KPIS。
     */
    public static final String QUERY_MONTHLY_KPIS = "queryMonthlyKpis";
    /**
     * 字段：CREATE_MARKETING_CONTENT_DRAFT。
     */
    public static final String CREATE_MARKETING_CONTENT_DRAFT = "createMarketingContentDraft";

    /** Exact service audience for a reviewed first-party HTTP MCP connection. */
    public static String firstPartyAudience(String serverName) {
        return FIRST_PARTY_AUDIENCES.get(serverName);
    }

    /**
     * 处理AgentMcpNames。
     */
    private AgentMcpNames() {
    }
}
