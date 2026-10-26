package com.bkanent.settlement.security;

import com.bkanent.common.authz.AuthzIngressFilter;
import com.bkanent.settlement.config.SettlementAuthorizationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "settlement.authorization", name = "enabled", havingValue = "true")
public class SettlementAuthorizationIngressFilter extends AuthzIngressFilter {
    public SettlementAuthorizationIngressFilter(SettlementAuthorizationProperties properties) {
        super("/settlements", properties.getAudience(), properties.getMaxBodyBytes(), properties.getAuthorityPublicKeys());
    }
}
