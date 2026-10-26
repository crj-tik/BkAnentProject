package com.bkanent.promotion.config;

import com.bkanent.common.authz.AuthzIngressProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "promotion.authorization")
public class PromotionAuthorizationProperties extends AuthzIngressProperties {
    public PromotionAuthorizationProperties() {
        setAudience("promotion-service");
    }

    @PostConstruct
    public void validateProperties() {
        validate("promotion-service");
    }
}
