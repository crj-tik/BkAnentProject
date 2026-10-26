package com.bkanent.business.security;

import com.bkanent.business.config.BusinessAuthorizationProperties;
import com.bkanent.common.authz.AuthzIngressFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "business.authorization", name = "enabled", havingValue = "true")
public class BusinessAuthorizationIngressFilter extends AuthzIngressFilter {
    public BusinessAuthorizationIngressFilter(BusinessAuthorizationProperties properties) {
        super("/business", properties.getAudience(), properties.getMaxBodyBytes(), properties.getAuthorityPublicKeys());
    }
}
