package com.bkanent.knowledge.security;

import com.bkanent.common.authz.AuthzIngressFilter;
import com.bkanent.knowledge.config.KnowledgeAuthorizationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "knowledge.authorization", name = "enabled", havingValue = "true")
public class KnowledgeAuthorizationIngressFilter extends AuthzIngressFilter {
    public KnowledgeAuthorizationIngressFilter(KnowledgeAuthorizationProperties properties) {
        super("/platform-operations/knowledge", properties.getAudience(), properties.getMaxBodyBytes(), properties.getAuthorityPublicKeys());
    }
}
