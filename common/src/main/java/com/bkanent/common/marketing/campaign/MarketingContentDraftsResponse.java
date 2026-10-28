package com.bkanent.common.marketing.campaign;

import java.util.List;

/** Structured response for the persisted content drafts created by one request. */
public record MarketingContentDraftsResponse(List<MarketingContentDraftResponse> contents) {
    public MarketingContentDraftsResponse {
        contents = contents == null ? List.of() : List.copyOf(contents);
    }
}
