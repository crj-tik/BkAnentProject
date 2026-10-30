package com.bkanent.business.security;

import com.bkanent.business.config.AnalyticsAuthorizationProperties;
import com.bkanent.common.authz.AuthzIngressFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "platform-operations.analytics.authorization", name = "enabled", havingValue = "true")
public class BusinessAuthorizationIngressFilter extends AuthzIngressFilter {
    public BusinessAuthorizationIngressFilter(AnalyticsAuthorizationProperties properties) {
        super("/platform-operations/analytics", properties.getAudience(), properties.getMaxBodyBytes(), properties.getAuthorityPublicKeys());
    }
}
