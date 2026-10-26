package com.bkanent.compare.security;

import com.bkanent.common.authz.AuthzIngressFilter;
import com.bkanent.compare.config.CompareAuthorizationProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "compare.authorization", name = "enabled", havingValue = "true")
public class CompareAuthorizationIngressFilter extends AuthzIngressFilter {
    public CompareAuthorizationIngressFilter(CompareAuthorizationProperties properties) {
        super("/compare", properties.getAudience(), properties.getMaxBodyBytes(),
                properties.getAuthorityPublicKeys());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean capabilityRead = "GET".equals(request.getMethod())
                && (path.matches("/compare/reports/share/[A-Za-z0-9_-]{32,64}")
                    || path.matches("/compare/reports/pdf/[A-Za-z0-9_-]{32,64}"))
                && (request.getHeader("Authorization") == null
                    || request.getHeader("Authorization").isBlank());
        return capabilityRead || super.shouldNotFilter(request);
    }
}
