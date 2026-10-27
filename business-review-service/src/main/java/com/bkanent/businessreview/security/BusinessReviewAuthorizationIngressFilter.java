package com.bkanent.businessreview.security;
import com.bkanent.common.authz.AuthzIngressFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(prefix = "business-review.authorization", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BusinessReviewAuthorizationIngressFilter extends AuthzIngressFilter {
    public BusinessReviewAuthorizationIngressFilter(BusinessReviewAuthorizationProperties properties) {
        super("/business-review", properties.getAudience(), properties.getMaxBodyBytes(), properties.getAuthorityPublicKeys());
    }
}
