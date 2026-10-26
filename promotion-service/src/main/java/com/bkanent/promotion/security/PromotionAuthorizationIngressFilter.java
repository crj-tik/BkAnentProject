package com.bkanent.promotion.security;

import com.bkanent.common.authz.AuthzIngressFilter;
import com.bkanent.promotion.config.PromotionAuthorizationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "promotion.authorization", name = "enabled", havingValue = "true")
public class PromotionAuthorizationIngressFilter extends AuthzIngressFilter {
    public PromotionAuthorizationIngressFilter(PromotionAuthorizationProperties properties) {
        super("/promotions", properties.getAudience(), properties.getMaxBodyBytes(),
                properties.getAuthorityPublicKeys());
    }
}
