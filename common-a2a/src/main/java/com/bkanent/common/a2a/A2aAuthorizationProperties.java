package com.bkanent.common.a2a;

import com.bkanent.common.security.ServiceIdentityCrypto;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local trust configuration for the opt-in, request-bound A2A authorization transport. */
@ConfigurationProperties("agent.a2a.authorization")
public class A2aAuthorizationProperties {
    private boolean enabled;
    private Map<String, String> authorityPublicKeys = new LinkedHashMap<>();
    /** Agent-service signing keys, keyed by the exact key ID in CallerProof. */
    private Map<String, String> trustedCallerPublicKeys = new LinkedHashMap<>();

    @PostConstruct
    public void validate() {
        if (!enabled) return;
        if (authorityPublicKeys == null || authorityPublicKeys.isEmpty()
                || trustedCallerPublicKeys == null || trustedCallerPublicKeys.isEmpty()) {
            throw new IllegalStateException("A2A authorization trust keys are required when enabled");
        }
        authorityKeys();
        trustedCallerKeys();
    }

    public Map<String, PublicKey> authorityKeys() { return decode(authorityPublicKeys); }
    public Map<String, PublicKey> trustedCallerKeys() { return decode(trustedCallerPublicKeys); }

    private static Map<String, PublicKey> decode(Map<String, String> configured) {
        try {
            if (configured == null || configured.isEmpty()) throw new IllegalArgumentException();
            Map<String, PublicKey> result = new LinkedHashMap<>();
            configured.forEach((keyId, encoded) -> {
                if (keyId == null || !keyId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
                        || encoded == null || encoded.isBlank()) throw new IllegalArgumentException();
                result.put(keyId, ServiceIdentityCrypto.decodePublicKey(encoded));
            });
            return Map.copyOf(result);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid A2A authorization trust key registration");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public Map<String, String> getAuthorityPublicKeys() { return authorityPublicKeys; }
    public void setAuthorityPublicKeys(Map<String, String> value) {
        authorityPublicKeys = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }
    public Map<String, String> getTrustedCallerPublicKeys() { return trustedCallerPublicKeys; }
    public void setTrustedCallerPublicKeys(Map<String, String> value) {
        trustedCallerPublicKeys = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }
    @Override public String toString() { return "A2aAuthorizationProperties[REDACTED]"; }
}
