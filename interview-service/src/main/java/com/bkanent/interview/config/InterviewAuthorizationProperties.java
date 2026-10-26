package com.bkanent.interview.config;

import com.bkanent.common.authz.AuthzContract;
import com.bkanent.common.authz.AuthzWireCodec;
import com.bkanent.common.security.ServiceIdentityCrypto;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

/** Local verification only; no Auth or Redis call is made from Interview actions. */
@ConfigurationProperties(prefix = "interview.authorization")
public class InterviewAuthorizationProperties {
    private boolean enabled;
    private Map<String, String> authorityPublicKeys = new HashMap<>();
    private int maxBodyBytes = AuthzContract.MAX_BODY_BYTES;
    private int participantTicketTtlMinutes = 15;

    public void validate() {
        if (!enabled) return;
        if (maxBodyBytes <= 0 || maxBodyBytes > AuthzContract.MAX_BODY_BYTES
                || authorityPublicKeys == null || authorityPublicKeys.isEmpty()) {
            throw new IllegalStateException("Invalid Interview authorization verification configuration");
        }
        verificationKeys();
    }

    public Map<String, PublicKey> verificationKeys() {
        Map<String, PublicKey> result = new HashMap<>();
        if (authorityPublicKeys == null) return Map.of();
        authorityPublicKeys.forEach((keyId, encoded) -> {
            if (keyId == null || !keyId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) {
                throw new IllegalStateException("Invalid Interview authorization key id");
            }
            if (encoded != null && !encoded.isBlank()) {
                result.put(keyId, ServiceIdentityCrypto.decodePublicKey(encoded));
            }
        });
        if (enabled && result.isEmpty()) throw new IllegalStateException("Interview authorization key is missing");
        return Map.copyOf(result);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Map<String, String> getAuthorityPublicKeys() { return authorityPublicKeys; }
    public void setAuthorityPublicKeys(Map<String, String> value) { this.authorityPublicKeys = value; }
    public int getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(int value) { this.maxBodyBytes = value; }
    public int getParticipantTicketTtlMinutes() { return participantTicketTtlMinutes; }
    public void setParticipantTicketTtlMinutes(int value) { this.participantTicketTtlMinutes = value; }
}
