package com.bkanent.compare.config;

import com.bkanent.common.authz.AuthzIngressProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "compare.authorization")
public class CompareAuthorizationProperties extends AuthzIngressProperties {
    public CompareAuthorizationProperties() {
        setAudience("compare-engine-service");
    }

    @PostConstruct
    public void validateProperties() {
        validate("compare-engine-service");
    }
}
