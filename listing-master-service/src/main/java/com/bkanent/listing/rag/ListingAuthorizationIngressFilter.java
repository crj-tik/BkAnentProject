package com.bkanent.listing.rag;

import com.bkanent.common.authz.AuthzIngressFilter;
import com.bkanent.listing.config.ListingAuthorizationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "listing.authorization", name = "enabled", havingValue = "true")
public class ListingAuthorizationIngressFilter extends AuthzIngressFilter {
    public ListingAuthorizationIngressFilter(ListingAuthorizationProperties properties) {
        super("/listings", properties.getAudience(), properties.getMaxBodyBytes(), properties.getAuthorityPublicKeys());
    }
}
