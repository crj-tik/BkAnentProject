package com.bkanent.listing.rag;

import com.bkanent.common.rag.*;
import com.bkanent.listing.config.ListingRagProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;

/** Domain-owned business baseline. No per-listing grants or inferred broker/tenant ownership. */
@Service
public class ListingRagGrantService {
    private final ListingRagProperties properties;

    public ListingRagGrantService(JdbcTemplate jdbc, ListingRagIdentity identity,
                                  ListingRagOutbox outbox, ListingRagProperties properties) {
        this.properties = properties;
    }

    public RagAuthorizationScope scope(AuthRagPrincipal principal, RagSearchRequest request) {
        boolean activeOnly = ListingRagIdentity.activeOnly(principal);
        if (principal.versionedAuthorization()) {
            if (!principal.hasPermission("listing.read")) throw new RagException(RagErrorCode.FORBIDDEN);
            long now = Instant.now().getEpochSecond();
            long expiry = Math.min(now + 60, principal.expiresAtEpochSecond());
            return RagScopeSigner.sign(new RagAuthorizationScope(1, properties.getKeyId(), ListingRagIdentity.SERVICE,
                    ListingRagIdentity.SERVICE, ListingRagIdentity.SPACE, String.valueOf(principal.userId()), RagOperation.SEARCH,
                    RagCanonicalHash.requestHash(request), properties.getPolicyVersion(), now, expiry,
                    null, List.of(), activeOnly, true, AuthRagPrincipal.VERSIONED_AUTHORIZATION_VERSION,
                    principal.permissionRevision(), principal.credentialEpoch(), principal.businessAdmin(), "listing.read",
                    principal, null, null),
                    ListingRagIdentity.bytes(properties.getSecret()));
        }
        long now = Instant.now().getEpochSecond();
        long expiry = Math.min(now + 60, principal.expiresAtEpochSecond());
        return RagScopeSigner.sign(new RagAuthorizationScope(1, properties.getKeyId(), ListingRagIdentity.SERVICE,
                ListingRagIdentity.SERVICE, ListingRagIdentity.SPACE, String.valueOf(principal.userId()), RagOperation.SEARCH,
                RagCanonicalHash.requestHash(request), properties.getPolicyVersion(), now, expiry,
                null, List.of(), activeOnly, true, null), ListingRagIdentity.bytes(properties.getSecret()));
    }
}
