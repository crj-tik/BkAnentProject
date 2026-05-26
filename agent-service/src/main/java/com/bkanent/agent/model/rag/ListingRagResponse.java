package com.bkanent.agent.model.rag;

import java.util.List;

/**
 * ListingRagResponse 数据对象。
 */
public record ListingRagResponse(
        String query,
        String assembledContext,
        List<ListingRagMatch> matches
) {
}
