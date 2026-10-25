package com.bkanent.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * GatewayAccessProperties 网关访问控制配置属性类。
 */
@ConfigurationProperties(prefix = "gateway.access")
public class GatewayAccessProperties {

    private GatewayAuthorizationProperties authorization;

    public GatewayAccessProperties() { }

    @org.springframework.beans.factory.annotation.Autowired
    public void setAuthorization(GatewayAuthorizationProperties authorization) {
        this.authorization = authorization;
    }

    public boolean isAuthorizationEnabled() { return authorization != null && authorization.isEnabled(); }

    public boolean isWhitelisted(String path, String method) {
        if (!isAuthorizationEnabled()) return isWhitelisted(path);
        return authorization.getPublicRoutes().stream().anyMatch(rule -> rule.path().equals(path)
                && rule.method().equals(method));
    }

    private List<String> whitelistPrefixes = new ArrayList<>(List.of(
            "/auth/login",
            "/auth/logout",
            "/auth/health",
            "/actuator",
            "/gateway/health"
    ));

    public List<String> getWhitelistPrefixes() {
        return whitelistPrefixes;
    }

    public void setWhitelistPrefixes(List<String> whitelistPrefixes) {
        this.whitelistPrefixes = whitelistPrefixes;
    }

    public boolean isWhitelisted(String path) {
        // A path-only call cannot establish a public rule in authorization mode.
        return !isAuthorizationEnabled() && whitelistPrefixes.stream().anyMatch(path::startsWith);
    }
}
