package com.bkanent.businessreview.security;
import com.bkanent.common.a2a.VerifiedDomainAuthorization;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthorizationContext;
import org.springframework.stereotype.Component;
import java.util.Set;

/** Auth currently signs BROKER identities only; STORE remains fail-closed until Auth registers it. */
@Component
public class VerifiedStoreScopeAdapter implements TrustedStoreScopePort {
    @Override
    public StoreScope requireStore(Long requestedStoreId, String operation) {
        VerifiedDomainAuthorization.requireOperation(operation);
        VerifiedDomainAuthorization.VerifiedContext verified = VerifiedDomainAuthorization.requireVerifiedContext();
        AuthorizationContext context = verified.context();
        if (!"business-review-service".equals(context.audience()))
            throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        Long signedStoreId = context.businessIdentities().get("STORE");
        if (signedStoreId == null || requestedStoreId == null || !signedStoreId.equals(requestedStoreId))
            throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        return new StoreScope(Long.parseLong(context.actorId()), signedStoreId,
                Set.copyOf(context.allowedOperations()), context.permissionRevision());
    }
}
