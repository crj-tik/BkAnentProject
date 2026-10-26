package com.bkanent.media.security;

import com.bkanent.common.authz.AuthzIngressFilter;
import com.bkanent.media.config.MediaAuthorizationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "media.authorization", name = "enabled", havingValue = "true")
public class MediaAuthorizationIngressFilter extends AuthzIngressFilter {
    public MediaAuthorizationIngressFilter(MediaAuthorizationProperties properties) {
        super("/media/tasks", properties.getAudience(), properties.getMaxBodyBytes(),
                properties.getAuthorityPublicKeys());
    }
}
