package com.bkanent.agent.config;

import com.bkanent.common.authz.AuthzContract;
import com.bkanent.common.security.ServiceIdentityCrypto;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opt-in signed-ingress consumer and bounded direct-service admission settings. */
@ConfigurationProperties(prefix = "agent.authorization")
public class AgentAuthorizationProperties {
    private boolean enabled;
    private String callerService = "agent-service";
    private String keyId;
    private String privateKeyPath;
    private String publicKey;
    private String audience = "agent-service";
    private int maxBodyBytes = AuthzContract.MAX_BODY_BYTES;
    private Map<String, String> authorityPublicKeys = new LinkedHashMap<>();
    private int cacheEntries = 1000;
    private long cacheBytes = 16 * 1024 * 1024;
    private int maxUsers = 1000;
    private int maxSessions = 4000;
    private int maxSessionsPerUser = 32;
    private Map<String, String> capabilityPermissions = new LinkedHashMap<>();

    @PostConstruct
    public void validate() {
        if (!enabled) return;
        if (!"agent-service".equals(audience) || maxBodyBytes <= 0 || maxBodyBytes > AuthzContract.MAX_BODY_BYTES
                || cacheEntries <= 0 || cacheEntries > 100000 || cacheBytes <= 0 || maxUsers <= 0
                || maxSessions <= 0 || maxSessionsPerUser <= 0) {
            throw new IllegalStateException("Invalid Agent authorization configuration");
        }
        authorityKeys();
        signingKey();
        getCapabilityPermissions().forEach((capability, permission) -> {
            new com.bkanent.common.agent.CapabilityId(capability);
            if (permission == null || permission.length() > 128
                    || !permission.matches("[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)+"))
                throw new IllegalStateException("Invalid Agent capability permission mapping");
        });
    }

    public java.security.PrivateKey signingKey() {
        try {
            if (!"agent-service".equals(callerService) || !callerService.equals(audience)
                    || keyId == null || !keyId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
                    || privateKeyPath == null || privateKeyPath.isBlank() || publicKey == null || publicKey.isBlank())
                throw new IllegalArgumentException();
            var key = ServiceIdentityCrypto.loadPrivateKey(java.nio.file.Path.of(privateKeyPath));
            var pub = ServiceIdentityCrypto.decodePublicKey(publicKey);
            byte[] check = "agent-execution-binding-key-check".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (!ServiceIdentityCrypto.verify(com.bkanent.common.authz.AuthzSignature.SIGNATURE_DOMAIN,
                    AuthzContract.ALGORITHM, check,
                    ServiceIdentityCrypto.sign(com.bkanent.common.authz.AuthzSignature.SIGNATURE_DOMAIN,
                            AuthzContract.ALGORITHM, check, key), pub)) throw new IllegalArgumentException();
            return key;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid Agent authorization service identity");
        }
    }
    public String getCallerService() { return callerService; }
    public void setCallerService(String value) { callerService = value; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String value) { keyId = value; }
    public String getPrivateKeyPath() { return privateKeyPath; }
    public void setPrivateKeyPath(String value) { privateKeyPath = value; }
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String value) { publicKey = value; }

    public Map<String, PublicKey> authorityKeys() {
        try {
            if (authorityPublicKeys == null || authorityPublicKeys.isEmpty()) throw new IllegalArgumentException();
            Map<String, PublicKey> keys = new LinkedHashMap<>();
            authorityPublicKeys.forEach((id, value) -> {
                if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) throw new IllegalArgumentException();
                keys.put(id, ServiceIdentityCrypto.decodePublicKey(value));
            });
            return Map.copyOf(keys);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid Agent authorization authority registration");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getAudience() { return audience; }
    public void setAudience(String value) { audience = value; }
    public int getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(int value) { maxBodyBytes = value; }
    public Map<String, String> getAuthorityPublicKeys() { return authorityPublicKeys; }
    public void setAuthorityPublicKeys(Map<String, String> value) { authorityPublicKeys = value; }
    public int getCacheEntries() { return cacheEntries; }
    public void setCacheEntries(int value) { cacheEntries = value; }
    public long getCacheBytes() { return cacheBytes; }
    public void setCacheBytes(long value) { cacheBytes = value; }
    public int getMaxUsers() { return maxUsers; }
    public void setMaxUsers(int value) { maxUsers = value; }
    public int getMaxSessions() { return maxSessions; }
    public void setMaxSessions(int value) { maxSessions = value; }
    public int getMaxSessionsPerUser() { return maxSessionsPerUser; }
    public void setMaxSessionsPerUser(int value) { maxSessionsPerUser = value; }
    public Map<String, String> getCapabilityPermissions() { return Map.copyOf(capabilityPermissions); }
    public void setCapabilityPermissions(Map<String, String> value) {
        capabilityPermissions = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }
    public String permissionForCapability(String capabilityId) {
        return capabilityPermissions.get(capabilityId);
    }
    @Override public String toString() { return "AgentAuthorizationProperties[REDACTED]"; }
}
