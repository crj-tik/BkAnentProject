package com.bkanent.agent.tool.context;

import com.bkanent.agent.milvus.core.model.MilvusSearchResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AgentToolContextHolder 持有器。
 */
public final class AgentToolContextHolder {

    private static final ThreadLocal<AgentToolContext> CONTEXT = new ThreadLocal<>();

    /**
     * 处理AgentToolContextHolder。
     */
    private AgentToolContextHolder() {
    }

    /**
     * 初始化。
     */
    public static void init(String collectionName, Integer topK, boolean allowKnowledgeSearch) {
        CONTEXT.set(new AgentToolContext(collectionName, topK, allowKnowledgeSearch));
    }

    /**
     * 处理current。
     */
    public static AgentToolContext current() {
        AgentToolContext context = CONTEXT.get();
        if (context == null) {
            context = new AgentToolContext(null, null, true);
            CONTEXT.set(context);
        }
        return context;
    }

    /**
     * 处理snapshot。
     */
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

    /**
     * 清理。
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * 记录tool。
     */
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

    /**
     * 记录mcpTool。
     */
    public static void recordMcpTool(String serverName, String toolName, String request, String response) {
        recordTool(serverName + "/" + toolName, request, response);
    }

    /**
     * 记录milvus。
     */
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

    /**
     * 处理safeText。
     */
    private static String safeText(String value) {
        return value == null || value.isBlank() ? "无" : value;
    }

    public static final class AgentToolContext {

        /**
         * 字段：collectionName。
         */
        private final String collectionName;
        /**
         * 字段：topK。
         */
        private final Integer topK;
        /**
         * 字段：allowKnowledgeSearch。
         */
        private final boolean allowKnowledgeSearch;
        private final List<MilvusSearchResult> milvusResults = new ArrayList<>();
        private final List<String> toolTraces = new ArrayList<>();
        private final Set<String> toolNames = new LinkedHashSet<>();
        /**
         * 字段：firstToolName。
         */
        private String firstToolName;
        /**
         * 字段：firstToolQuery。
         */
        private String firstToolQuery;

        /**
         * 处理AgentToolContext。
         */
        private AgentToolContext(String collectionName, Integer topK, boolean allowKnowledgeSearch) {
            this.collectionName = collectionName;
            this.topK = topK;
            this.allowKnowledgeSearch = allowKnowledgeSearch;
        }

        /**
         * 获取collectionName。
         */
        public String getCollectionName() {
            return collectionName;
        }

        /**
         * 获取topK。
         */
        public Integer getTopK() {
            return topK;
        }

        /**
         * 判断是否allowKnowledgeSearch。
         */
        public boolean isAllowKnowledgeSearch() {
            return allowKnowledgeSearch;
        }
    }
}
