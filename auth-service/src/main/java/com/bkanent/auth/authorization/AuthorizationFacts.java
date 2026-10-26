package com.bkanent.auth.authorization;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Database facts only; these records are not authentication or resource-scope proofs. */
public record AuthorizationFacts(User user, List<Permission> catalog, List<RoleGrant> grants,
                                 Map<String, Long> businessIdentities) {
    public AuthorizationFacts {
        catalog = List.copyOf(catalog);
        grants = List.copyOf(grants);
        businessIdentities = com.bkanent.common.authz.AuthzContract.businessIdentities(businessIdentities);
    }

    public AuthorizationFacts(User user, List<Permission> catalog, List<RoleGrant> grants) {
        this(user, catalog, grants, Map.of());
    }

    public record User(long userId, boolean active, boolean deleted, boolean businessAdmin,
                       long permissionRevision, long credentialEpoch, String status) {
    }

    public record Permission(String code, String domain, String operation, boolean active, long version) {
    }

    public record RoleGrant(long roleId, String roleCode, boolean roleActive, boolean roleDeleted,
                            boolean grantActive, Instant validFrom, Instant validUntil,
                            Set<String> permissionCodes) {
        public RoleGrant {
            permissionCodes = Set.copyOf(permissionCodes);
        }
    }
}
