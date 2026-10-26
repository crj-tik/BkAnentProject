package com.bkanent.media.config;

import com.bkanent.common.security.ServiceIdentityCrypto;
import com.bkanent.common.authz.AuthzIngressProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local verification keys for bounded Auth-derived background media permits. */
@Component
@ConfigurationProperties(prefix = "media.authorization")
public class MediaAuthorizationProperties extends AuthzIngressProperties {
    private String signingKeyId = "media-background-current";
    private String signingSecret;

    public MediaAuthorizationProperties() { setAudience("media-worker-service"); }
    public String getSigningKeyId() { return signingKeyId; }
    public void setSigningKeyId(String signingKeyId) { this.signingKeyId = signingKeyId; }
    public String getSigningSecret() { return signingSecret; }
    public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }
    public Map<String, PublicKey> decodedAuthorityPublicKeys() {
        Map<String, PublicKey> decoded = new LinkedHashMap<>();
        getAuthorityPublicKeys().forEach((id, value) -> {
            if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) {
                throw new IllegalStateException("Invalid media authorization key id");
            }
            decoded.put(id, ServiceIdentityCrypto.decodePublicKey(value));
        });
        return Map.copyOf(decoded);
    }

    public byte[] signingSecretBytes() {
        return signingSecret == null ? new byte[0] : signingSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public void validateEnabledConfiguration() {
        if (!isEnabled()) return;
        if (signingKeyId == null || !signingKeyId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
                || signingSecretBytes().length < 32 || getAuthorityPublicKeys().isEmpty()) {
            throw new IllegalStateException("Invalid media authorization permit configuration");
        }
        validate("media-worker-service");
        if (decodedAuthorityPublicKeys().isEmpty()) throw new IllegalStateException(
                "Media authorization requires Auth authority public keys");
    }
}
