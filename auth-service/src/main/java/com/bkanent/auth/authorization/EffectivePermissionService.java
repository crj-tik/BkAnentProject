package com.bkanent.auth.authorization;

import com.bkanent.common.authz.AuthzOperationPolicy;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Pure explicit-operation calculation. Business resource authorization remains in each domain. */
public final class EffectivePermissionService {
    public Snapshot calculate(AuthorizationFacts facts, Instant now) {
        Objects.requireNonNull(now, "now");
        AuthorizationFacts.User user = Objects.requireNonNull(facts.user(), "user");
        long catalogVersion = facts.catalog().stream().mapToLong(AuthorizationFacts.Permission::version)
                .max().orElse(0);
        TreeSet<String> registered = new TreeSet<>();
        facts.catalog().stream().filter(AuthorizationFacts.Permission::active)
                .filter(permission -> !AuthzOperationPolicy.isBaseline(permission.code()))
                .map(AuthorizationFacts.Permission::code).forEach(registered::add);
        TreeSet<String> permissions = new TreeSet<>();
        Instant deadline = null;
        boolean eligible = user.active() && !user.deleted() && "ACTIVE".equals(user.status());
        if (eligible && user.businessAdmin()) {
            permissions.addAll(registered);
        } else if (eligible) {
            for (AuthorizationFacts.RoleGrant grant : facts.grants()) {
                if (!grant.grantActive() || !grant.roleActive() || grant.roleDeleted()) {
                    continue;
                }
                // Future starts are also a snapshot change point. A scheduler must publish a new revision.
                if (grant.validFrom() != null && grant.validFrom().isAfter(now)) {
                    if (grant.validUntil() == null || grant.validFrom().isBefore(grant.validUntil())) {
                        deadline = earlier(deadline, grant.validFrom());
                    }
                    continue;
                }
                if (grant.validUntil() != null && !now.isBefore(grant.validUntil())) {
                    continue;
                }
                deadline = earlier(deadline, grant.validUntil());
                grant.permissionCodes().stream().filter(registered::contains).forEach(permissions::add);
            }
        }
        return new Snapshot(user.userId(), user.permissionRevision(), user.credentialEpoch(),
                eligible ? "ACTIVE" : user.deleted() ? "DELETED" : "DISABLED",
                eligible && user.businessAdmin(), catalogVersion, List.copyOf(permissions), deadline,
                eligible ? facts.businessIdentities() : Map.of());
    }

    private static Instant earlier(Instant first, Instant second) {
        return second == null ? first : first == null || second.isBefore(first) ? second : first;
    }

    public record Snapshot(long userId, long permissionRevision, long credentialEpoch, String status,
                           boolean businessAdmin, long catalogVersion, List<String> permissions,
                           Instant grantDeadline, Map<String, Long> businessIdentities) {
        public Snapshot {
            permissions = List.copyOf(new TreeSet<>(permissions));
            businessIdentities = com.bkanent.common.authz.AuthzContract.businessIdentities(businessIdentities);
        }

        public Snapshot(long userId, long permissionRevision, long credentialEpoch, String status,
                        boolean businessAdmin, long catalogVersion, List<String> permissions,
                        Instant grantDeadline) {
            this(userId, permissionRevision, credentialEpoch, status, businessAdmin, catalogVersion,
                    permissions, grantDeadline, Map.of());
        }

        public boolean hasPermission(String exactCode, Instant now) {
            return exactCode != null && "ACTIVE".equals(status)
                    && (grantDeadline == null || now.isBefore(grantDeadline))
                    && Collections.binarySearch(permissions, exactCode) >= 0;
        }

        public Set<String> permissionSet() {
            return Collections.unmodifiableSet(new TreeSet<>(permissions));
        }
    }
}
