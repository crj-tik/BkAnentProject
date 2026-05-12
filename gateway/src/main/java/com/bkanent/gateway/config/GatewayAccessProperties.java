package com.bkanent.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * GatewayAccessProperties 网关访问控制配置属性类。
 */
@ConfigurationProperties(prefix = "gateway.access")
public class GatewayAccessProperties {

    private List<String> whitelistPrefixes = new ArrayList<>(List.of(
            "/auth/login",
            "/auth/logout",
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
        return whitelistPrefixes.stream().anyMatch(path::startsWith);
    }
}
