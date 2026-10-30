package com.bkanent.businessreview.security;
import com.bkanent.common.authz.AuthzIngressFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(prefix = "platform-operations.review.authorization", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BusinessReviewAuthorizationIngressFilter extends AuthzIngressFilter {
    public BusinessReviewAuthorizationIngressFilter(BusinessReviewAuthorizationProperties properties) {
        super("/platform-operations/reviews", properties.getAudience(), properties.getMaxBodyBytes(), properties.getAuthorityPublicKeys());
    }
}
