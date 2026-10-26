package com.bkanent.marketing.config;

import com.bkanent.common.authz.AuthzIngressProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "marketing.authorization")
public class MarketingAuthorizationProperties extends AuthzIngressProperties {
    public MarketingAuthorizationProperties() {
        setAudience("marketing-content-service");
    }

    @PostConstruct
    public void validateProperties() {
        validate("marketing-content-service");
    }
}
