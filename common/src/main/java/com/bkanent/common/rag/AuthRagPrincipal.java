package com.bkanent.common.rag;

import com.bkanent.common.authz.AuthzOperationPolicy;

import java.io.Serializable;
import java.util.List;
import java.util.TreeSet;

/** Current auth facts only; production consumers must verify the auth-service response attestation. */
public record AuthRagPrincipal(Long userId, String roleCode, boolean accountActive, boolean roleActive,
                               long expiresAtEpochSecond, int authorizationVersion, long permissionRevision,
                               long credentialEpoch, boolean businessAdmin, List<String> roles,
                               List<String> permissions, RagAuthEnvelope attestation) implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int LEGACY_AUTHORIZATION_VERSION = 1;
    public static final int VERSIONED_AUTHORIZATION_VERSION = 2;

    public AuthRagPrincipal(Long userId, String roleCode, boolean accountActive, boolean roleActive,
                            long expiresAtEpochSecond) {
        this(userId, roleCode, accountActive, roleActive, expiresAtEpochSecond, null);
    }

    public AuthRagPrincipal(Long userId, String roleCode, boolean accountActive, boolean roleActive,
                            long expiresAtEpochSecond, RagAuthEnvelope attestation) {
        this(userId, roleCode, accountActive, roleActive, expiresAtEpochSecond, LEGACY_AUTHORIZATION_VERSION,
                0, 0, false, roleCode == null || roleCode.isBlank() ? List.of() : List.of(roleCode), List.of(), attestation);
    }

    public AuthRagPrincipal {
        if (authorizationVersion == 0 && permissionRevision == 0 && credentialEpoch == 0
                && !businessAdmin && (permissions == null || permissions.isEmpty())) {
            authorizationVersion = LEGACY_AUTHORIZATION_VERSION; // Old serialized providers omit the additive fields.
        }
        roles = normalized(roles);
        permissions = normalized(permissions);
        if (authorizationVersion == LEGACY_AUTHORIZATION_VERSION) {
            if (permissionRevision != 0 || credentialEpoch != 0 || businessAdmin || !permissions.isEmpty()) {
                throw new RagException(RagErrorCode.INVALID_ARGUMENT, "authorizationVersion");
            }
        } else if (authorizationVersion == VERSIONED_AUTHORIZATION_VERSION) {
            if (permissionRevision <= 0 || credentialEpoch < 0) {
                throw new RagException(RagErrorCode.INVALID_ARGUMENT, "authorizationVersion");
            }
        } else {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "authorizationVersion");
        }
    }

    private static List<String> normalized(List<String> values) {
        if (values == null) return List.of();
        if (values.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 128
                || value.codePoints().anyMatch(Character::isISOControl))) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "authorizationFacts");
        }
        return List.copyOf(new TreeSet<>(values));
    }

    public boolean versionedAuthorization() {
        return authorizationVersion == VERSIONED_AUTHORIZATION_VERSION && permissionRevision > 0;
    }

    public boolean hasPermission(String permissionCode) {
        return versionedAuthorization() && permissionCode != null
                && (permissions.contains(permissionCode) || AuthzOperationPolicy.isBaseline(permissionCode));
    }

    public AuthRagPrincipal withAttestation(RagAuthEnvelope value) {
        return new AuthRagPrincipal(userId, roleCode, accountActive, roleActive, expiresAtEpochSecond,
                authorizationVersion, permissionRevision, credentialEpoch, businessAdmin, roles, permissions, value);
    }

    @Override
    public String toString() { return "AuthRagPrincipal[REDACTED]"; }
}
