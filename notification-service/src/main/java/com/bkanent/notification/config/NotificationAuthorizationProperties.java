package com.bkanent.notification.config;

import com.bkanent.common.authz.AuthzIngressProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "notification.authorization")
public class NotificationAuthorizationProperties extends AuthzIngressProperties {
    private Map<String, TrustedSystemCaller> trustedSystemCallers = new LinkedHashMap<>();

    public NotificationAuthorizationProperties() {
        setAudience("notification-service");
    }

    @PostConstruct
    public void validateProperties() {
        validate("notification-service");
    }

    public Map<String, TrustedSystemCaller> getTrustedSystemCallers() { return trustedSystemCallers; }

    public void setTrustedSystemCallers(Map<String, TrustedSystemCaller> callers) {
        trustedSystemCallers = callers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(callers);
    }

    public static class TrustedSystemCaller {
        private String callerService;
        private String publicKey;

        public String getCallerService() { return callerService; }
        public void setCallerService(String callerService) { this.callerService = callerService; }
        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    }
}
