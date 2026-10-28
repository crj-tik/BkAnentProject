package com.bkanent.marketing.campaign;

import com.bkanent.common.marketing.campaign.CampaignErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class CampaignAssetAdminService {
    private final CampaignJdbcRepository repository;
    private final CampaignActorResolver actorResolver;
    private final CampaignAuthorizationService authorization;

    public CampaignAssetAdminService(CampaignJdbcRepository repository, CampaignActorResolver actorResolver,
                                     CampaignAuthorizationService authorization) {
        this.repository = repository; this.actorResolver = actorResolver; this.authorization = authorization;
    }

    @Transactional
    public CampaignBrandAssetResponse create(CampaignBrandAssetRequest request) {
        long actor = actorResolver.currentActorId(); authorization.require(actor, CampaignAuthorizationService.ASSET_MANAGE);
        if (request == null || !StringUtils.hasText(request.assetType()) || !StringUtils.hasText(request.assetName())
                || !StringUtils.hasText(request.assetUrl()) || request.assetName().length() > 128
                || request.assetUrl().length() > 1024 || !request.assetUrl().startsWith("https://")) {
            throw CampaignDomainException.invalid("brand asset requires type, name, and an HTTPS URL");
        }
        if (request.tags().size() > 32 || request.tags().stream().anyMatch(tag -> tag == null || tag.length() > 64 || tag.contains(","))) {
            throw CampaignDomainException.invalid("brand asset tags are invalid");
        }
        String status = request.status() == null ? "ACTIVE" : request.status().toUpperCase();
        if (!List.of("ACTIVE", "INACTIVE").contains(status)) throw CampaignDomainException.invalid("brand asset status is invalid");
        long id = repository.createAsset(new CampaignBrandAssetRequest(request.assetType().toUpperCase(), request.assetName(),
                request.assetUrl(), request.platformScope(), request.tags(), status, request.remark()));
        return repository.listAssets(null, null).stream().filter(asset -> asset.id() == id).findFirst().orElseThrow();
    }

    public List<CampaignBrandAssetResponse> list(String assetType, String keyword) {
        long actor = actorResolver.currentActorId(); authorization.require(actor, CampaignAuthorizationService.PLAN_READ);
        return repository.listAssets(assetType, keyword);
    }
}
