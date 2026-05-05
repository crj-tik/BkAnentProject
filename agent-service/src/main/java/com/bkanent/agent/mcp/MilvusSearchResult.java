package com.bkanent.agent.mcp;

public record MilvusSearchResult(
        String collection,
        String content,
        Double score
) {
}
