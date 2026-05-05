package com.bkanent.agent.rag;

import com.bkanent.agent.mcp.MilvusSearchResult;

import java.util.List;

public record ListingRagResponse(
        String query,
        String assembledContext,
        List<MilvusSearchResult> matches
) {
}
