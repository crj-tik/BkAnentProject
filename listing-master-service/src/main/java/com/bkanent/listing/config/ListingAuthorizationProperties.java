package com.bkanent.listing.config;

import com.bkanent.common.authz.AuthzIngressProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "listing.authorization")
public class ListingAuthorizationProperties extends AuthzIngressProperties {
    public ListingAuthorizationProperties() { setAudience("listing-master-service"); }
    @PostConstruct public void validateProperties() { validate("listing-master-service"); }
}
