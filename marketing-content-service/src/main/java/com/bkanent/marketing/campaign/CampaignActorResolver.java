package com.bkanent.marketing.campaign;

import com.bkanent.common.a2a.A2aCredentialContext;
import com.bkanent.common.marketing.campaign.CampaignErrorCode;
import com.bkanent.common.rag.RagCredentialContext;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class CampaignActorResolver {

    private final ObjectProvider<AuthPermissionRpcService> authProvider;

    public CampaignActorResolver(ObjectProvider<AuthPermissionRpcService> authProvider) {
        this.authProvider = authProvider;
    }

    public long currentActorId() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            String authorization = servlet.getRequest().getHeader("Authorization");
            if (authorization != null) return resolveBearer(authorization);
        }

        RagCredentialContext credentials;
        String actor;
        try {
            credentials = A2aCredentialContext.currentCredentials();
            actor = A2aCredentialContext.currentActorId();
        } catch (RuntimeException failure) {
            throw CampaignDomainException.unauthorized("authenticated user context is required");
        }
        if (credentials == null || actor == null || !actor.matches("[1-9][0-9]{0,18}")) {
            throw CampaignDomainException.unauthorized("authenticated user context is required");
        }
        long actorId;
        try {
            actorId = Long.parseLong(actor);
        } catch (NumberFormatException failure) {
            throw CampaignDomainException.unauthorized("authenticated user context is invalid");
        }
        if (credentials.accessToken() != null) {
            AuthenticatedPrincipal principal = resolveToken(credentials.accessToken());
            if (principal.userId() != actorId) throw CampaignDomainException.unauthorized("authenticated user context is invalid");
        }
        return actorId;
    }

    private long resolveBearer(String authorization) {
        if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                || authorization.substring(7).isBlank()) {
            throw CampaignDomainException.unauthorized("a valid bearer token is required");
        }
        AuthenticatedPrincipal principal = resolveToken(authorization.substring(7).trim());
        if (principal.userId() == null || principal.userId() <= 0) {
            throw CampaignDomainException.unauthorized("authenticated user context is invalid");
        }
        return principal.userId();
    }

    private AuthenticatedPrincipal resolveToken(String token) {
        AuthPermissionRpcService auth = authProvider.getIfAvailable();
        if (auth == null) {
            throw CampaignDomainException.unavailable(CampaignErrorCode.DEPENDENCY_UNAVAILABLE,
                    "authorization service is unavailable");
        }
        try {
            AuthenticatedPrincipal principal = auth.resolvePrincipal(token);
            if (principal == null || principal.userId() == null || principal.userId() <= 0) {
                throw CampaignDomainException.unauthorized("authenticated user context is invalid");
            }
            return principal;
        } catch (CampaignDomainException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw CampaignDomainException.unavailable(CampaignErrorCode.DEPENDENCY_UNAVAILABLE,
                    "authorization service is unavailable");
        }
    }
}
