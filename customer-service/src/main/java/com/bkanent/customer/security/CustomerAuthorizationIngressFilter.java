package com.bkanent.customer.security;

import com.bkanent.common.authz.AuthzIngressFilter;
import com.bkanent.customer.config.CustomerAuthorizationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "customer.authorization", name = "enabled", havingValue = "true")
public class CustomerAuthorizationIngressFilter extends AuthzIngressFilter {
    public CustomerAuthorizationIngressFilter(CustomerAuthorizationProperties properties) {
        super("/customers", properties.getAudience(), properties.getMaxBodyBytes(), properties.getAuthorityPublicKeys());
    }
}
