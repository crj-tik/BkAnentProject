package com.bkanent.listing.rag;

import com.bkanent.common.authz.AuthzIngressFilter;
import com.bkanent.listing.config.ListingAuthorizationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The old REST namespace is routed to Listing during migration and uses the same signed Auth proof. */
@Component
@ConditionalOnProperty(prefix = "listing.authorization", name = "enabled", havingValue = "true")
public class ListingComparisonAuthorizationIngressFilter extends AuthzIngressFilter {
    public ListingComparisonAuthorizationIngressFilter(ListingAuthorizationProperties properties) {
        super("/compare", properties.getAudience(), properties.getMaxBodyBytes(), properties.getAuthorityPublicKeys());
    }
}
