package com.bkanent.marketing.campaign;

import com.bkanent.marketing.model.MarketingContentDetailResponse;
import com.bkanent.marketing.service.MarketingAssetService;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class CampaignContentReviewService {
    private static final Set<String> REVIEW_STATES = Set.of("APPROVED", "REJECTED", "REVIEWING");
    private final CampaignActorResolver actorResolver;
    private final CampaignAuthorizationService authorization;
    private final MarketingAssetService marketingAssets;

    public CampaignContentReviewService(CampaignActorResolver actorResolver, CampaignAuthorizationService authorization,
                                        MarketingAssetService marketingAssets) {
        this.actorResolver = actorResolver; this.authorization = authorization; this.marketingAssets = marketingAssets;
    }

    public MarketingContentDetailResponse review(long contentId, CampaignContentReviewRequest request) {
        long actorId = actorResolver.currentActorId();
        authorization.require(actorId, CampaignAuthorizationService.CONTENT_REVIEW);
        if (request == null || request.auditStatus() == null || !REVIEW_STATES.contains(request.auditStatus().toUpperCase())) {
            throw CampaignDomainException.invalid("review decision must be APPROVED, REJECTED, or REVIEWING");
        }
        return marketingAssets.updateAuditStatus(contentId, request.auditStatus());
    }
}
