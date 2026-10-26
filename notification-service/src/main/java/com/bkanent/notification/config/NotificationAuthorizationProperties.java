package com.bkanent.notification.config;

import com.bkanent.common.authz.AuthzIngressProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "notification.authorization")
public class NotificationAuthorizationProperties extends AuthzIngressProperties {
    public NotificationAuthorizationProperties() {
        setAudience("notification-service");
    }

    @PostConstruct
    public void validateProperties() {
        validate("notification-service");
    }
}
