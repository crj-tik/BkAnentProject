package com.bkanent.contract.config;

import com.bkanent.common.authz.AuthzIngressProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "contract.authorization")
public class ContractAuthorizationProperties extends AuthzIngressProperties {
    public ContractAuthorizationProperties() {
        setAudience("contract-service");
    }

    @PostConstruct
    public void validateProperties() {
        validate("contract-service");
    }
}
