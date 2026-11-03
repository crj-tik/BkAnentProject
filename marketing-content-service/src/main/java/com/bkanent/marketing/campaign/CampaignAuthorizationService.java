package com.bkanent.marketing.campaign;

import com.bkanent.common.a2a.VerifiedDomainAuthorization;
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

    public CampaignAuthorizationService(ObjectProvider<AuthPermissionRpcService> unused) { }

    public void require(long actorId, String permissionCode) {
        VerifiedDomainAuthorization.requireVerifiedContext();
        if (VerifiedDomainAuthorization.requireActorId() != actorId) {
            throw CampaignDomainException.forbidden("authorization actor mismatch");
        }
        VerifiedDomainAuthorization.requirePermission(permissionCode);
    }
}
