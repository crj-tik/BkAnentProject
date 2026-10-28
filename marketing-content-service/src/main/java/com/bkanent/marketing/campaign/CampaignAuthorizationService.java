package com.bkanent.marketing.campaign;

import com.bkanent.common.marketing.campaign.CampaignErrorCode;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class CampaignAuthorizationService {

    public static final String PLAN_READ = "marketing.campaign.read";
    public static final String PLAN_PREPARE = "marketing.campaign.prepare";
    public static final String PLAN_CONFIRM = "marketing.campaign.publish";
    public static final String EFFECT_READ = "marketing.campaign.effects.read";
    public static final String ASSET_MANAGE = "marketing.campaign.assets.manage";
    public static final String CONTENT_REVIEW = "marketing.content.review";

    private final ObjectProvider<AuthPermissionRpcService> authProvider;

    public CampaignAuthorizationService(ObjectProvider<AuthPermissionRpcService> authProvider) {
        this.authProvider = authProvider;
    }

    public void require(long actorId, String permissionCode) {
        AuthPermissionRpcService auth = authProvider.getIfAvailable();
        if (auth == null) {
            throw CampaignDomainException.unavailable(CampaignErrorCode.DEPENDENCY_UNAVAILABLE,
                    "authorization service is unavailable");
        }
        boolean permitted;
        try {
            permitted = auth.hasPermission(actorId, permissionCode);
        } catch (RuntimeException failure) {
            throw CampaignDomainException.unavailable(CampaignErrorCode.DEPENDENCY_UNAVAILABLE,
                    "authorization service is unavailable");
        }
        if (!permitted) throw CampaignDomainException.forbidden("permission denied");
    }
}
