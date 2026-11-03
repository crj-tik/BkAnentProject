package com.bkanent.marketing.campaign;

import com.bkanent.common.a2a.VerifiedDomainAuthorization;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class CampaignActorResolver {
    public CampaignActorResolver(ObjectProvider<AuthPermissionRpcService> unused) { }

    public long currentActorId() {
        VerifiedDomainAuthorization.requireVerifiedContext();
        return VerifiedDomainAuthorization.requireActorId();
    }
}
