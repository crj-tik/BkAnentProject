package com.bkanent.notification.security;

import com.bkanent.common.authz.AuthzIngressFilter;
import com.bkanent.notification.config.NotificationAuthorizationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "notification.authorization", name = "enabled", havingValue = "true")
public class NotificationAuthorizationIngressFilter extends AuthzIngressFilter {
    public NotificationAuthorizationIngressFilter(NotificationAuthorizationProperties properties) {
        super("/notifications", properties.getAudience(), properties.getMaxBodyBytes(),
                properties.getAuthorityPublicKeys());
    }
}
