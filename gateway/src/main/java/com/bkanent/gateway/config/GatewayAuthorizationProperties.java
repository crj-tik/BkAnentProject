package com.bkanent.gateway.config;

import com.bkanent.common.authz.AuthzContract;
import com.bkanent.common.security.ServiceIdentityCrypto;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit opt-in; mappings are exact identities, never inferred from a URL/path suffix. */
@ConfigurationProperties(prefix = "gateway.authorization")
public class GatewayAuthorizationProperties {
    private boolean enabled;
    private int maxBodyBytes = AuthzContract.MAX_BODY_BYTES;
    private String callerService = "gateway";
    private String keyId;
    private String privateKeyPath;
    private Map<String, String> authorityPublicKeys = new LinkedHashMap<>();
    private Map<String, String> routeAudiences = new LinkedHashMap<>();
    private Map<String, String> serviceAudiences = new LinkedHashMap<>();
    private List<PublicRoute> publicRoutes = new ArrayList<>(List.of(
            new PublicRoute("POST", "/auth/login"), new PublicRoute("POST", "/auth/logout"),
            new PublicRoute("POST", "/auth/refresh"), new PublicRoute("GET", "/auth/health"),
            new PublicRoute("GET", "/gateway/health")));

    @PostConstruct
    public void validate() {
        if (!enabled) return;
        if (maxBodyBytes <= 0 || maxBodyBytes > AuthzContract.MAX_BODY_BYTES
                || !"gateway".equals(callerService) || keyId == null
                || !keyId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
                || privateKeyPath == null || privateKeyPath.isBlank()
                || authorityPublicKeys == null || authorityPublicKeys.isEmpty()) {
            throw new IllegalStateException("Invalid gateway authorization configuration");
        }
        authorityPublicKeys.forEach((id, key) -> {
            if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) {
                throw new IllegalStateException("Invalid authority key registration");
            }
            ServiceIdentityCrypto.decodePublicKey(key);
        });
        validateMappings(routeAudiences);
        validateMappings(serviceAudiences);
        if (publicRoutes == null || publicRoutes.stream().anyMatch(rule -> rule == null
                || rule.method() == null || !rule.method().matches("[A-Z]{1,16}")
                || rule.path() == null || !rule.path().startsWith("/")
                || rule.path().contains("*") || rule.path().startsWith("/actuator")
                || rule.path().contains("%") || rule.path().contains("?"))) {
            throw new IllegalStateException("Public authorization routes must be exact non-management paths");
        }
    }

    private void validateMappings(Map<String, String> mappings) {
        if (mappings == null || mappings.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null
                || !entry.getValue().matches("[a-z][a-z0-9-]{0,95}"))) {
            throw new IllegalStateException("Invalid exact route audience mapping");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(int maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
    public String getCallerService() { return callerService; }
    public void setCallerService(String callerService) { this.callerService = callerService; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public String getPrivateKeyPath() { return privateKeyPath; }
    public void setPrivateKeyPath(String privateKeyPath) { this.privateKeyPath = privateKeyPath; }
    public Map<String, String> getAuthorityPublicKeys() { return authorityPublicKeys; }
    public void setAuthorityPublicKeys(Map<String, String> keys) { this.authorityPublicKeys = keys; }
    public Map<String, String> getRouteAudiences() { return routeAudiences; }
    public void setRouteAudiences(Map<String, String> mappings) { this.routeAudiences = mappings; }
    public Map<String, String> getServiceAudiences() { return serviceAudiences; }
    public void setServiceAudiences(Map<String, String> mappings) { this.serviceAudiences = mappings; }
    public List<PublicRoute> getPublicRoutes() { return publicRoutes; }
    public void setPublicRoutes(List<PublicRoute> routes) { this.publicRoutes = routes; }
    public record PublicRoute(String method, String path) { }
}
