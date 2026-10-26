package com.bkanent.contract.security;

import com.bkanent.common.authz.AuthzIngressFilter;
import com.bkanent.contract.config.ContractAuthorizationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "contract.authorization", name = "enabled", havingValue = "true")
public class ContractAuthorizationIngressFilter extends AuthzIngressFilter {
    public ContractAuthorizationIngressFilter(ContractAuthorizationProperties properties) {
        super("/contracts", properties.getAudience(), properties.getMaxBodyBytes(),
                properties.getAuthorityPublicKeys());
    }
}
