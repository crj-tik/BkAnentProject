package com.bkanent.common.mcp.authz;

import com.bkanent.common.authz.AuthzContract;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.security.ServiceIdentityCrypto;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Service-owned MCP authorization allowlists and registered verification keys. */
@ConfigurationProperties(prefix = "mcp.authorization.server")
public class McpAuthorizationServerProperties {
    private boolean enabled;
    private String callerService = "agent-service";
    private int maxBodyBytes = AuthzContract.MAX_BODY_BYTES;
    private int replayCacheEntries = 10000;
    private Map<String, String> authorityPublicKeys = new LinkedHashMap<>();
    private Map<String, String> callerPublicKeys = new LinkedHashMap<>();
    private Map<String, String> toolOperations = new LinkedHashMap<>();
    private Set<String> publicTools = new LinkedHashSet<>();

    public void validate(String audience, String endpoint) {
        if (!enabled) return;
        if (audience == null || !audience.matches("[a-z][a-z0-9-]{0,95}")
                || !"agent-service".equals(callerService)
                || maxBodyBytes < 1024 || maxBodyBytes > AuthzContract.MAX_BODY_BYTES
                || replayCacheEntries < 1 || replayCacheEntries > 1_000_000
                || endpoint == null || !endpoint.startsWith("/") || endpoint.contains("%") || endpoint.contains("..")) {
            throw new IllegalStateException("Invalid MCP authorization server configuration");
        }
        authorityKeys();
        callerKeys();
        if (toolOperations.isEmpty() && publicTools.isEmpty()) {
            throw new IllegalStateException("MCP authorization server requires an explicit tool allowlist");
        }
        toolOperations.forEach((tool, operation) -> {
            if (!validTool(tool) || operation == null || operation.length() > 128
                    || !operation.matches("[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)+")) {
                throw new IllegalStateException("Invalid MCP tool operation mapping");
            }
            if (publicTools.contains(tool)) throw new IllegalStateException("MCP tool cannot be both public and delegated");
        });
        if (publicTools.stream().anyMatch(tool -> !validTool(tool))) {
            throw new IllegalStateException("Invalid public MCP tool allowlist");
        }
    }

    private static boolean validTool(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    }

    public Map<String, PublicKey> authorityKeys() { return decodeKeys(authorityPublicKeys); }
    public Map<String, PublicKey> callerKeys() { return decodeKeys(callerPublicKeys); }

    private static Map<String, PublicKey> decodeKeys(Map<String, String> encoded) {
        try {
            if (encoded == null || encoded.isEmpty()) throw new IllegalArgumentException();
            Map<String, PublicKey> keys = new LinkedHashMap<>();
            encoded.forEach((id, value) -> {
                if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) throw new IllegalArgumentException();
                keys.put(id, ServiceIdentityCrypto.decodePublicKey(value));
            });
            return Map.copyOf(keys);
        } catch (RuntimeException failure) {
            throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getCallerService() { return callerService; }
    public void setCallerService(String value) { callerService = value; }
    public int getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(int value) { maxBodyBytes = value; }
    public int getReplayCacheEntries() { return replayCacheEntries; }
    public void setReplayCacheEntries(int value) { replayCacheEntries = value; }
    public Map<String, String> getAuthorityPublicKeys() { return authorityPublicKeys; }
    public void setAuthorityPublicKeys(Map<String, String> value) { authorityPublicKeys = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value); }
    public Map<String, String> getCallerPublicKeys() { return callerPublicKeys; }
    public void setCallerPublicKeys(Map<String, String> value) { callerPublicKeys = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value); }
    public Map<String, String> getToolOperations() { return Map.copyOf(toolOperations); }
    public void setToolOperations(Map<String, String> value) { toolOperations = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value); }
    public Set<String> getPublicTools() { return Set.copyOf(publicTools); }
    public void setPublicTools(Set<String> value) { publicTools = value == null ? new LinkedHashSet<>() : new LinkedHashSet<>(value); }
}
