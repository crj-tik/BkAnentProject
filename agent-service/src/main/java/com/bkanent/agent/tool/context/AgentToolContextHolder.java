package com.bkanent.agent.tool.context;

import com.bkanent.agent.model.vector.MilvusSearchResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 工具调用上下文持有器。
 */
public final class AgentToolContextHolder {

    private static final ThreadLocal<AgentToolContext> CONTEXT = new ThreadLocal<>();

    private AgentToolContextHolder() {
    }

    public static void init(String collectionName, Integer topK, boolean allowKnowledgeSearch) {
        CONTEXT.set(new AgentToolContext(collectionName, topK, allowKnowledgeSearch));
    }

    public static AgentToolContext current() {
        AgentToolContext context = CONTEXT.get();
        if (context == null) {
            context = new AgentToolContext(null, null, true);
            CONTEXT.set(context);
        }
        return context;
    }

    public static AgentToolSessionSnapshot snapshot() {
        AgentToolContext context = CONTEXT.get();
        if (context == null) {
            return new AgentToolSessionSnapshot(false, null, null, null, List.of(), "");
        }
        return new AgentToolSessionSnapshot(
                !context.toolNames.isEmpty(),
                context.firstToolName,
                context.firstToolQuery,
                context.topK,
                List.copyOf(context.milvusResults),
                String.join(System.lineSeparator() + System.lineSeparator(), context.toolTraces)
        );
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public static void recordTool(String toolName, String request, String response) {
        AgentToolContext context = current();
        context.toolNames.add(toolName);
        if (context.firstToolName == null) {
            context.firstToolName = toolName;
            context.firstToolQuery = request;
        }
        context.toolTraces.add("""
                工具名称：%s
                请求内容：%s
                响应内容：%s
                """.formatted(toolName, safeText(request), safeText(response)).trim());
    }

    public static void recordMcpTool(String serverName, String toolName, String request, String response) {
        recordTool(serverName + "/" + toolName, request, response);
    }

    public static void recordMilvus(String query, List<MilvusSearchResult> results) {
        AgentToolContext context = current();
        context.milvusResults.clear();
        if (results != null) {
            context.milvusResults.addAll(results);
        }
        if (context.firstToolQuery == null) {
            context.firstToolQuery = query;
        }
    }

    private static String safeText(String value) {
        return value == null || value.isBlank() ? "无" : value;
    }

    /**
     * 单次请求的工具上下文。
     */
    public static final class AgentToolContext {

        private final String collectionName;
        private final Integer topK;
        private final boolean allowKnowledgeSearch;
        private final List<MilvusSearchResult> milvusResults = new ArrayList<>();
        private final List<String> toolTraces = new ArrayList<>();
        private final Set<String> toolNames = new LinkedHashSet<>();
        private String firstToolName;
        private String firstToolQuery;

        private AgentToolContext(String collectionName, Integer topK, boolean allowKnowledgeSearch) {
            this.collectionName = collectionName;
            this.topK = topK;
            this.allowKnowledgeSearch = allowKnowledgeSearch;
        }

        public String getCollectionName() {
            return collectionName;
        }

        public Integer getTopK() {
            return topK;
        }

        public boolean isAllowKnowledgeSearch() {
            return allowKnowledgeSearch;
        }
    }
}
