package com.bkanent.common.marketing.campaign;

/** Persisted draft identity returned to callers so it can be reviewed and prepared. */
public record MarketingContentDraftResponse(Long contentId, String platform, String status) {
}
