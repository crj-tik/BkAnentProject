package com.bkanent.marketing.security;

import com.bkanent.common.authz.AuthzIngressFilter;
import com.bkanent.marketing.config.MarketingAuthorizationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "marketing.authorization", name = "enabled", havingValue = "true")
public class MarketingAuthorizationIngressFilter extends AuthzIngressFilter {
    public MarketingAuthorizationIngressFilter(MarketingAuthorizationProperties properties) {
        super("/marketing", properties.getAudience(), properties.getMaxBodyBytes(),
                properties.getAuthorityPublicKeys());
    }
}
