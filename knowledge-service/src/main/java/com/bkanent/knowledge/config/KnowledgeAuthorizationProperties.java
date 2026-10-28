package com.bkanent.knowledge.config;

import com.bkanent.common.authz.AuthzIngressProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knowledge.authorization")
public class KnowledgeAuthorizationProperties extends AuthzIngressProperties {
    public KnowledgeAuthorizationProperties() {
        setAudience("knowledge-service");
    }

    @PostConstruct
    public void validateProperties() {
        validate("knowledge-service");
    }
}
