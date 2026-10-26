package com.bkanent.rag.config;

import com.bkanent.common.rag.*;
import com.bkanent.common.security.ServiceIdentityCrypto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("rag.security")
public record RagSecurityProperties(Map<String, TrustedKey> trustedKeys, String authRequestKeyId,
        String authRequestSecret, String authResponseKeyId, String authResponseSecret,
        boolean versionedAuthorizationEnabled, Map<String, String> authzAuthorityPublicKeys) {
    public RagSecurityProperties(Map<String, TrustedKey> trustedKeys, String authRequestKeyId,
            String authRequestSecret, String authResponseKeyId, String authResponseSecret) {
        this(trustedKeys, authRequestKeyId, authRequestSecret, authResponseKeyId, authResponseSecret, false, Map.of());
    }
    public RagSecurityProperties(Map<String, TrustedKey> trustedKeys, String authRequestKeyId,
            String authRequestSecret, String authResponseKeyId, String authResponseSecret,
            boolean versionedAuthorizationEnabled) {
        this(trustedKeys, authRequestKeyId, authRequestSecret, authResponseKeyId, authResponseSecret,
                versionedAuthorizationEnabled, Map.of());
    }
    @ConstructorBinding
    public RagSecurityProperties {
        trustedKeys = trustedKeys == null ? Map.of() : Map.copyOf(trustedKeys);
        authzAuthorityPublicKeys = authzAuthorityPublicKeys == null ? Map.of() : Map.copyOf(authzAuthorityPublicKeys);
    }
    public record TrustedKey(String callerService, String ownerDomain, Set<RagOperation> allowedOperations, String secret) {
        @Override public String toString() { return "TrustedKey[REDACTED]"; }
    }
    public Map<String, RagSigningKey> signingKeys() {
        Map<String, RagSigningKey> result = new HashMap<>();
        trustedKeys.forEach((id, key) -> {
            RagSigningKey mapped = new RagSigningKey(id, key.callerService(), key.ownerDomain(), key.allowedOperations(), bytes(key.secret()));
            for (RagSigningKey existing : result.values()) {
                if (!existing.callerService().equals(mapped.callerService())
                        && MessageDigest.isEqual(existing.secret(), mapped.secret())) {
                    throw new RagException(RagErrorCode.INVALID_ARGUMENT, "security.sharedServiceSecret");
                }
                if (existing.callerService().equals(mapped.callerService()) && !Objects.equals(existing.ownerDomain(), mapped.ownerDomain())) {
                    throw new RagException(RagErrorCode.INVALID_ARGUMENT, "security.ownerMapping");
                }
            }
            if (MessageDigest.isEqual(mapped.secret(), bytes(authRequestSecret))
                    || MessageDigest.isEqual(mapped.secret(), bytes(authResponseSecret))) {
                throw new RagException(RagErrorCode.INVALID_ARGUMENT, "security.sharedServiceSecret");
            }
            result.put(id, mapped);
        });
        if (authRequestSecret != null && !authRequestSecret.isBlank() && authResponseSecret != null
                && !authResponseSecret.isBlank() && MessageDigest.isEqual(bytes(authRequestSecret), bytes(authResponseSecret))) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "security.sharedAuthSecret");
        }
        return Map.copyOf(result);
    }
    public boolean configured() {
        return !trustedKeys.isEmpty() && present(authRequestKeyId) && present(authResponseKeyId)
                && bytes(authRequestSecret).length >= 32 && bytes(authResponseSecret).length >= 32;
    }
    public Map<String, PublicKey> authzVerificationKeys() {
        Map<String, PublicKey> result = new HashMap<>();
        authzAuthorityPublicKeys.forEach((keyId, encoded) -> {
            if (keyId == null || keyId.isBlank()) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "security.authzKeyId");
            if (encoded != null && !encoded.isBlank()) {
                try { result.put(keyId, ServiceIdentityCrypto.decodePublicKey(encoded)); }
                catch (IllegalArgumentException invalid) { throw new RagException(RagErrorCode.INVALID_ARGUMENT, "security.authzPublicKey"); }
            }
        });
        return Map.copyOf(result);
    }
    public static byte[] bytes(String value) { return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8); }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
    @Override public String toString() { return "RagSecurityProperties[REDACTED]"; }
}
