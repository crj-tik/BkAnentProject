package com.bkanent.auth.authorization;

import com.bkanent.auth.service.AuthTokenService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Organization node creation uses the existing authorization writer and audit boundary. */
@Service
@ConditionalOnProperty(prefix = "auth.authorization", name = "enabled", havingValue = "true")
public class OrganizationManagementService {
    private final AuthTokenService tokens;
    private final AuthorizationRepository repository;
    private final Clock clock;

    @Autowired
    public OrganizationManagementService(AuthTokenService tokens, AuthorizationRepository repository) {
        this(tokens, repository, Clock.systemUTC());
    }

    OrganizationManagementService(AuthTokenService tokens, AuthorizationRepository repository, Clock clock) {
        this.tokens = tokens;
        this.repository = repository;
        this.clock = clock;
    }

    public List<OrgUnit> list(String token) {
        return authenticated(token, actor -> repository.jdbc().query(
                "SELECT id,org_code,org_name,org_type,parent_id,status,store_business_id,revision FROM auth_org_unit ORDER BY id",
                (rs, row) -> readUnit(rs)));
    }

    public OrgUnit create(String token, CreateOrgUnit input) {
        return authenticated(token, actor -> {
            if (input == null || input.code() == null || !input.code().matches("[A-Za-z0-9._-]{1,64}")
                    || input.name() == null || input.name().isBlank() || input.name().trim().length() > 128
                    || input.type() == null || !List.of("COMPANY", "STORE", "DEPARTMENT").contains(input.type())) {
                throw error(HttpStatus.BAD_REQUEST, "Invalid organization data");
            }
            if ("COMPANY".equals(input.type())) {
                if (input.parentId() != null) throw error(HttpStatus.BAD_REQUEST, "Company must be a root");
            } else {
                if (input.parentId() == null || input.parentId() <= 0 || repository.jdbc().queryForObject(
                        "SELECT COUNT(*) FROM auth_org_unit WHERE id=? AND org_type='COMPANY' AND status='ACTIVE'",
                        Integer.class, input.parentId()) != 1) {
                    throw error(HttpStatus.BAD_REQUEST, "Active parent company required");
                }
            }
            var key = new GeneratedKeyHolder();
            try {
                repository.jdbc().update(connection -> {
                    var statement = connection.prepareStatement(
                            "INSERT INTO auth_org_unit(org_code,org_name,org_type,parent_id) VALUES (?,?,?,?)",
                            new String[]{"id"});
                    statement.setString(1, input.code());
                    statement.setString(2, input.name().trim());
                    statement.setString(3, input.type());
                    if (input.parentId() == null) statement.setNull(4, java.sql.Types.BIGINT);
                    else statement.setLong(4, input.parentId());
                    return statement;
                }, key);
            } catch (DuplicateKeyException duplicate) {
                throw error(HttpStatus.CONFLICT, "Organization code already exists");
            }
            long id = java.util.Objects.requireNonNull(key.getKey()).longValue();
            repository.jdbc().update("INSERT INTO auth_authorization_audit"
                            + "(audit_id,actor_id,actor_revision,action,target_type,target_id,event_id) VALUES (?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), actor.userId(), actor.permissionRevision(),
                    "ORG_CREATED", "ORG", id, null);
            return new OrgUnit(id, input.code(), input.name().trim(), input.type(), input.parentId(), "ACTIVE");
        });
    }

    /** An organization can be disabled only after all dependent scopes have been resolved. */
    public OrgUnit setStatus(String token, long orgId, String status) {
        return authenticated(token, actor -> {
            if (orgId <= 0 || status == null || !List.of("ACTIVE", "DISABLED").contains(status)) {
                throw error(HttpStatus.BAD_REQUEST, "Invalid organization status");
            }
            var units = repository.jdbc().query(
                    "SELECT id,org_code,org_name,org_type,parent_id,status,store_business_id,revision FROM auth_org_unit WHERE id=?",
                    (rs, row) -> readUnit(rs), orgId);
            if (units.isEmpty()) throw error(HttpStatus.NOT_FOUND, "Organization not found");
            OrgUnit unit = units.get(0);
            if (unit.status().equals(status)) return unit;
            if ("ACTIVE".equals(status) && unit.parentId() != null && repository.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM auth_org_unit WHERE id=? AND status='ACTIVE' AND org_type='COMPANY'",
                    Integer.class, unit.parentId()) != 1) {
                throw error(HttpStatus.CONFLICT, "Parent company is inactive");
            }
            if ("DISABLED".equals(status)) {
                int children = repository.jdbc().queryForObject(
                        "SELECT COUNT(*) FROM auth_org_unit WHERE parent_id=? AND status='ACTIVE'", Integer.class, orgId);
                int members = repository.jdbc().queryForObject(
                        "SELECT COUNT(*) FROM auth_org_membership WHERE org_id=? AND status='ACTIVE' "
                                + "AND (valid_until IS NULL OR valid_until>?)",
                        Integer.class, orgId, Timestamp.from(clock.instant()));
                int legacyScopes = repository.jdbc().queryForObject(
                        "SELECT COUNT(*) FROM auth_user_store_identity s JOIN auth_org_unit o "
                                + "ON o.store_business_id=s.store_id WHERE o.id=?", Integer.class, orgId);
                if (children != 0 || members != 0 || legacyScopes != 0) {
                    throw error(HttpStatus.CONFLICT, "Active organization dependencies must be resolved first");
                }
            }
            repository.jdbc().update("UPDATE auth_org_unit SET status=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    status, orgId);
            repository.jdbc().update("INSERT INTO auth_authorization_audit"
                            + "(audit_id,actor_id,actor_revision,action,target_type,target_id,event_id) VALUES (?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), actor.userId(), actor.permissionRevision(),
                    "ORG_STATUS_CHANGED", "ORG", orgId, null);
            return new OrgUnit(unit.id(), unit.code(), unit.name(), unit.type(), unit.parentId(), status,
                    unit.storeBusinessId(), unit.revision() + 1);
        });
    }

    /** Establishes the explicit one-to-one mapping used to derive trusted STORE scope from membership. */
    public StoreMappingChange setStoreBusinessId(String token, long orgId, Long storeBusinessId) {
        return authenticated(token, actor -> {
            if (orgId <= 0 || storeBusinessId != null && storeBusinessId <= 0) {
                throw error(HttpStatus.BAD_REQUEST, "Invalid store mapping");
            }
            var rows = repository.jdbc().query("SELECT id,org_type,store_business_id,status FROM auth_org_unit WHERE id=?",
                    (rs,row) -> {
                        long value = rs.getLong("store_business_id");
                        Long mapped = rs.wasNull() ? null : value;
                        return new StoreMappingRow(rs.getLong("id"), rs.getString("org_type"), mapped,
                                rs.getString("status"));
                    }, orgId);
            if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "Organization not found");
            StoreMappingRow current = rows.get(0);
            if (!"STORE".equals(current.type())) throw error(HttpStatus.BAD_REQUEST, "Only store organizations can be mapped");
            if (storeBusinessId != null && !"ACTIVE".equals(current.status())) {
                throw error(HttpStatus.CONFLICT, "Activate the store organization before assigning a business ID");
            }
            if (java.util.Objects.equals(current.storeBusinessId(), storeBusinessId)) {
                return new StoreMappingChange(orgId, storeBusinessId, null, List.of());
            }
            if (current.storeBusinessId() != null && repository.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM auth_user_store_identity WHERE store_id=?",
                    Integer.class, current.storeBusinessId()) != 0) {
                throw error(HttpStatus.CONFLICT, "Legacy store identities must be migrated before changing this mapping");
            }
            if (storeBusinessId != null && repository.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM auth_org_unit WHERE store_business_id=? AND id<>?",
                    Integer.class, storeBusinessId, orgId) != 0) {
                throw error(HttpStatus.CONFLICT, "Store business ID is already mapped");
            }
            var memberIds = repository.jdbc().queryForList("SELECT DISTINCT user_id FROM auth_org_membership "
                    + "WHERE org_id=? AND is_primary=1 AND status='ACTIVE' "
                    + "AND (valid_until IS NULL OR valid_until>?) ORDER BY user_id",
                    Long.class, orgId, Timestamp.from(clock.instant()));
            if (storeBusinessId == null && !memberIds.isEmpty()) {
                throw error(HttpStatus.CONFLICT, "End primary memberships before removing the store mapping");
            }
            if (storeBusinessId != null && repository.jdbc().queryForObject("""
                    SELECT COUNT(*) FROM auth_org_membership m JOIN auth_user_store_identity s ON s.user_id=m.user_id
                    WHERE m.org_id=? AND m.is_primary=1 AND m.status='ACTIVE' AND s.store_id<>?
                      AND (m.valid_until IS NULL OR m.valid_until>?)
                    """, Integer.class, orgId, storeBusinessId, Timestamp.from(clock.instant())) != 0) {
                throw error(HttpStatus.CONFLICT, "Existing member store scopes require verified migration");
            }
            var result = repository.mutateUsers(memberIds, "ORG_STORE_MAPPING_CHANGED", false, jdbc -> {
                if (jdbc.update("UPDATE auth_org_unit SET store_business_id=?,revision=revision+1,"
                                + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND revision=?",
                        storeBusinessId, orgId, repository.jdbc().queryForObject(
                                "SELECT revision FROM auth_org_unit WHERE id=?", Long.class, orgId)) != 1) {
                    throw error(HttpStatus.CONFLICT, "Store mapping changed concurrently");
                }
            });
            repository.jdbc().update("INSERT INTO auth_authorization_audit"
                            + "(audit_id,actor_id,actor_revision,action,target_type,target_id,event_id) VALUES (?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), actor.userId(), actor.permissionRevision(),
                    "ORG_STORE_MAPPING_CHANGED", "ORG", orgId, result.eventId());
            return new StoreMappingChange(orgId, storeBusinessId, result.eventId(), result.versions());
        });
    }

    /** Initial membership records do not confer store scope until the store mapping is verified. */
    public MemberChange addPrimaryMember(String token, long orgId, long userId) {
        return addMembership(token, orgId, new MembershipRequest(userId, true, null, null));
    }

    /** Records explicit primary or secondary membership with a bounded, auditable effective interval. */
    public MemberChange addMembership(String token, long orgId, MembershipRequest request) {
        return authenticated(token, actor -> {
            if (request == null || request.userId() == null || request.userId() <= 0 || orgId <= 0) {
                throw error(HttpStatus.BAD_REQUEST, "Invalid member");
            }
            long userId = request.userId();
            boolean primary = request.primary() == null || request.primary();
            var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var validFrom = request.validFrom() == null ? now
                    : request.validFrom().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var validUntil = request.validUntil() == null ? null
                    : request.validUntil().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            if (validFrom.isBefore(now) || validUntil != null
                    && (!validFrom.isBefore(validUntil) || !validUntil.isAfter(now))) {
                throw error(HttpStatus.BAD_REQUEST, "Membership interval must start now or later and end after its start");
            }
            if (repository.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM auth_org_unit WHERE id=? AND status='ACTIVE' AND org_type IN ('STORE','DEPARTMENT')",
                    Integer.class, orgId) != 1) {
                throw error(HttpStatus.BAD_REQUEST, "Active store or department required");
            }
            if (repository.jdbc().queryForObject("SELECT COUNT(*) FROM user_account u "
                    + "JOIN auth_user_authorization_state s ON s.user_id=u.id "
                    + "WHERE u.id=? AND u.deleted=0 AND u.account_status='ACTIVE' AND s.status='ACTIVE'",
                    Integer.class, userId) != 1) {
                throw error(HttpStatus.NOT_FOUND, "Active user not found");
            }
            String overlap = " AND (valid_until IS NULL OR valid_until>?) AND (valid_from IS NULL OR valid_from<?)";
            Object untilProbe = validUntil == null ? Timestamp.valueOf("9999-12-31 23:59:59") : Timestamp.from(validUntil);
            int duplicateMembership = repository.jdbc().queryForObject("SELECT COUNT(*) FROM auth_org_membership "
                            + "WHERE user_id=? AND org_id=? AND status='ACTIVE'" + overlap,
                    Integer.class, userId, orgId, Timestamp.from(validFrom), untilProbe);
            if (duplicateMembership != 0) throw error(HttpStatus.CONFLICT, "Overlapping membership already exists");
            if (primary) {
                int overlappingPrimary = repository.jdbc().queryForObject("SELECT COUNT(*) FROM auth_org_membership "
                                + "WHERE user_id=? AND status='ACTIVE' AND is_primary=1" + overlap,
                        Integer.class, userId, Timestamp.from(validFrom), untilProbe);
                if (overlappingPrimary != 0) throw error(HttpStatus.CONFLICT, "Overlapping primary membership already exists");
            }
            // Retire the legacy identity only when this explicit organization mapping proves the same store.
            List<Long> legacyStoreIds = repository.jdbc().queryForList(
                    "SELECT store_id FROM auth_user_store_identity WHERE user_id=?", Long.class, userId);
            List<Long> targetStoreIds = repository.jdbc().queryForList(
                    "SELECT store_business_id FROM auth_org_unit WHERE id=? AND org_type='STORE' AND store_business_id IS NOT NULL",
                    Long.class, orgId);
            boolean retireLegacyStore = primary && legacyStoreIds.size() == 1 && targetStoreIds.size() == 1
                    && legacyStoreIds.get(0).equals(targetStoreIds.get(0));
            if (primary && !legacyStoreIds.isEmpty() && !retireLegacyStore) {
                throw error(HttpStatus.CONFLICT, "Existing store scope requires a verified matching organization mapping");
            }
            var result = repository.mutateUsers(List.of(userId), "ORG_MEMBERSHIP_ADDED", false, jdbc -> {
                var key = new GeneratedKeyHolder();
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement("INSERT INTO auth_org_membership"
                            + "(user_id,org_id,is_primary,status,valid_from,valid_until) VALUES (?,?,?,'ACTIVE',?,?)",
                            new String[]{"id"});
                    statement.setLong(1, userId);
                    statement.setLong(2, orgId);
                    statement.setInt(3, primary ? 1 : 0);
                    statement.setTimestamp(4, Timestamp.from(validFrom));
                    if (validUntil == null) statement.setNull(5, java.sql.Types.TIMESTAMP);
                    else statement.setTimestamp(5, Timestamp.from(validUntil));
                    return statement;
                }, key);
                long membershipId = java.util.Objects.requireNonNull(key.getKey()).longValue();
                if (!validFrom.isAfter(now)) repository.markMembershipBoundaryProcessed(membershipId, "START", validFrom, now);
                if (retireLegacyStore && !validFrom.isAfter(now)) {
                    jdbc.update("DELETE FROM auth_user_store_identity WHERE user_id=? AND store_id=?",
                            userId, targetStoreIds.get(0));
                }
            });
            repository.jdbc().update("INSERT INTO auth_authorization_audit"
                            + "(audit_id,actor_id,actor_revision,action,target_type,target_id,event_id) VALUES (?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), actor.userId(), actor.permissionRevision(),
                    primary ? "ORG_MEMBER_ADDED" : "ORG_SECONDARY_MEMBER_ADDED", "USER", userId, result.eventId());
            return new MemberChange(userId, orgId, result.eventId(), result.versions().get(0).permissionRevision());
        });
    }

    public List<Member> members(String token, long orgId) {
        return authenticated(token, actor -> {
            if (orgId <= 0 || repository.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM auth_org_unit WHERE id=? AND status='ACTIVE'", Integer.class, orgId) != 1) {
                throw error(HttpStatus.NOT_FOUND, "Active organization not found");
            }
            Timestamp now = Timestamp.from(clock.instant());
            return repository.jdbc().query("SELECT user_id,org_id,is_primary,valid_from,valid_until,status FROM auth_org_membership "
                            + "WHERE org_id=? AND status='ACTIVE' ORDER BY user_id,valid_from,id",
                    (rs, row) -> {
                        Timestamp from = rs.getTimestamp("valid_from");
                        Timestamp until = rs.getTimestamp("valid_until");
                        boolean effective = (from == null || !from.after(now)) && (until == null || until.after(now));
                        return new Member(rs.getLong("user_id"), rs.getLong("org_id"),
                                rs.getInt("is_primary") == 1, instant(from), instant(until), effective);
                    }, orgId);
        });
    }

    /** Ending an initial membership is allowed only while no legacy store scope needs migration. */
    public MemberChange endPrimaryMember(String token, long orgId, long userId) {
        return authenticated(token, actor -> {
            if (orgId <= 0 || userId <= 0) throw error(HttpStatus.BAD_REQUEST, "Invalid member");
            var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var memberships = repository.jdbc().query("SELECT id,valid_from FROM auth_org_membership "
                            + "WHERE org_id=? AND user_id=? AND status='ACTIVE' AND is_primary=1 "
                            + "AND (valid_until IS NULL OR valid_until>?) "
                            + "ORDER BY CASE WHEN valid_from IS NULL OR valid_from<=? THEN 0 ELSE 1 END,valid_from,id LIMIT 1",
                    (rs, row) -> new ActiveMembership(rs.getLong(1), rs.getTimestamp(2)),
                    orgId, userId, Timestamp.from(now), Timestamp.from(now));
            if (memberships.isEmpty()) throw error(HttpStatus.NOT_FOUND, "Active primary membership not found");
            ActiveMembership membership = memberships.get(0);
            boolean started = membership.validFrom() == null || !membership.validFrom().toInstant().isAfter(now);
            List<Long> legacyStoreIds = repository.jdbc().queryForList(
                    "SELECT store_id FROM auth_user_store_identity WHERE user_id=?", Long.class, userId);
            List<Long> currentOrgStoreIds = repository.jdbc().queryForList("""
                    SELECT store_business_id FROM auth_org_unit WHERE id=? AND org_type='STORE' AND store_business_id IS NOT NULL
                    """, Long.class, orgId);
            boolean retireLegacyStore = legacyStoreIds.size() == 1 && currentOrgStoreIds.size() == 1
                    && legacyStoreIds.get(0).equals(currentOrgStoreIds.get(0));
            if (!legacyStoreIds.isEmpty() && !retireLegacyStore) {
                throw error(HttpStatus.CONFLICT, "Existing store scope requires a verified matching organization mapping");
            }
            var result = repository.mutateUsers(List.of(userId), "ORG_MEMBERSHIP_ENDED", false, jdbc -> {
                String sql = started
                        ? "UPDATE auth_org_membership SET status='ENDED',valid_until=? WHERE id=? AND status='ACTIVE'"
                        : "UPDATE auth_org_membership SET status='ENDED' WHERE id=? AND status='ACTIVE'";
                int updated = started
                        ? jdbc.update(sql, Timestamp.from(now), membership.id())
                        : jdbc.update(sql, membership.id());
                if (updated != 1) {
                    throw error(HttpStatus.CONFLICT, "Membership changed");
                }
                if (retireLegacyStore && started) {
                    jdbc.update("DELETE FROM auth_user_store_identity WHERE user_id=?", userId);
                }
            });
            repository.jdbc().update("INSERT INTO auth_authorization_audit"
                            + "(audit_id,actor_id,actor_revision,action,target_type,target_id,event_id) VALUES (?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), actor.userId(), actor.permissionRevision(),
                    "ORG_MEMBER_ENDED", "USER", userId, result.eventId());
            return new MemberChange(userId, orgId, result.eventId(), result.versions().get(0).permissionRevision());
        });
    }

    /** Transfers only within the same active company and preserves membership history. */
    public MemberChange movePrimaryMember(String token, long fromOrgId, long userId, long toOrgId) {
        return authenticated(token, actor -> {
            if (fromOrgId <= 0 || toOrgId <= 0 || userId <= 0 || fromOrgId == toOrgId) {
                throw error(HttpStatus.BAD_REQUEST, "Invalid member transfer");
            }
            var parents = repository.jdbc().queryForList(
                    "SELECT parent_id FROM auth_org_unit WHERE id IN (?,?) AND status='ACTIVE' "
                            + "AND org_type IN ('STORE','DEPARTMENT') ORDER BY id", Long.class, fromOrgId, toOrgId);
            if (parents.size() != 2 || parents.get(0) == null || !parents.get(0).equals(parents.get(1))
                    || repository.jdbc().queryForObject(
                            "SELECT COUNT(*) FROM auth_org_unit WHERE id=? AND org_type='COMPANY' AND status='ACTIVE'",
                            Integer.class, parents.get(0)) != 1) {
                throw error(HttpStatus.CONFLICT, "Organizations must belong to the same active company");
            }
            if (repository.jdbc().queryForObject("SELECT COUNT(*) FROM user_account u "
                            + "JOIN auth_user_authorization_state s ON s.user_id=u.id "
                            + "WHERE u.id=? AND u.deleted=0 AND u.account_status='ACTIVE' AND s.status='ACTIVE'",
                    Integer.class, userId) != 1) throw error(HttpStatus.NOT_FOUND, "Active user not found");
            var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var starts = repository.jdbc().query("SELECT valid_from FROM auth_org_membership "
                            + "WHERE org_id=? AND user_id=? AND status='ACTIVE' AND is_primary=1 "
                            + "AND (valid_from IS NULL OR valid_from<=?) AND (valid_until IS NULL OR valid_until>?)",
                    (rs, row) -> rs.getTimestamp(1), fromOrgId, userId, Timestamp.from(now), Timestamp.from(now));
            if (starts.size() != 1 || repository.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM auth_org_membership WHERE user_id=? AND status='ACTIVE' AND is_primary=1 "
                            + "AND (valid_from IS NULL OR valid_from<=?) AND (valid_until IS NULL OR valid_until>?)",
                    Integer.class, userId, Timestamp.from(now), Timestamp.from(now)) != 1)
                throw error(HttpStatus.CONFLICT, "Current primary membership is missing or ambiguous");
            if (repository.jdbc().queryForObject("SELECT COUNT(*) FROM auth_org_membership "
                            + "WHERE org_id=? AND user_id=? AND status='ACTIVE' "
                            + "AND (valid_until IS NULL OR valid_until>?)",
                    Integer.class, toOrgId, userId, Timestamp.from(now)) != 0) {
                throw error(HttpStatus.CONFLICT, "Target organization already has an overlapping membership");
            }
            List<Long> legacyStoreIds = repository.jdbc().queryForList(
                    "SELECT store_id FROM auth_user_store_identity WHERE user_id=?", Long.class, userId);
            List<Long> sourceStoreIds = repository.jdbc().queryForList("""
                    SELECT store_business_id FROM auth_org_unit WHERE id=? AND org_type='STORE' AND store_business_id IS NOT NULL
                    """, Long.class, fromOrgId);
            boolean retireLegacyStore = legacyStoreIds.size() == 1 && sourceStoreIds.size() == 1
                    && legacyStoreIds.get(0).equals(sourceStoreIds.get(0));
            if (!legacyStoreIds.isEmpty() && !retireLegacyStore) {
                throw error(HttpStatus.CONFLICT, "Existing store scope requires a verified matching organization mapping");
            }
            var result = repository.mutateUsers(List.of(userId), "ORG_MEMBERSHIP_MOVED", false, jdbc -> {
                if (jdbc.update("UPDATE auth_org_membership SET status='ENDED',valid_until=? "
                                + "WHERE org_id=? AND user_id=? AND status='ACTIVE' AND is_primary=1",
                        Timestamp.from(now), fromOrgId, userId) != 1) throw error(HttpStatus.CONFLICT, "Membership changed");
                var key = new GeneratedKeyHolder();
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement("INSERT INTO auth_org_membership"
                            + "(user_id,org_id,is_primary,status,valid_from) VALUES (?,?,1,'ACTIVE',?)",
                            new String[]{"id"});
                    statement.setLong(1, userId);
                    statement.setLong(2, toOrgId);
                    statement.setTimestamp(3, Timestamp.from(now));
                    return statement;
                }, key);
                repository.markMembershipBoundaryProcessed(
                        java.util.Objects.requireNonNull(key.getKey()).longValue(), "START", now, now);
                if (retireLegacyStore) jdbc.update("DELETE FROM auth_user_store_identity WHERE user_id=?", userId);
            });
            repository.jdbc().update("INSERT INTO auth_authorization_audit"
                            + "(audit_id,actor_id,actor_revision,action,target_type,target_id,event_id) VALUES (?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), actor.userId(), actor.permissionRevision(),
                    "ORG_MEMBER_MOVED", "USER", userId, result.eventId());
            return new MemberChange(userId, toOrgId, result.eventId(), result.versions().get(0).permissionRevision());
        });
    }

    private <T> T authenticated(String token, Function<EffectivePermissionService.Snapshot, T> work) {
        authenticate(token);
        return repository.withWriterLock(() -> work.apply(authenticate(token)));
    }

    private EffectivePermissionService.Snapshot authenticate(String token) {
        var principal = tokens.parse(token);
        if (principal == null || !"access".equals(principal.tokenType()))
            throw error(HttpStatus.UNAUTHORIZED, "Valid access token required");
        var snapshot = repository.snapshot(principal.userId(), clock.instant())
                .orElseThrow(() -> error(HttpStatus.UNAUTHORIZED, "Unknown authorization state"));
        if (!"ACTIVE".equals(snapshot.status())) throw error(HttpStatus.UNAUTHORIZED, "Inactive user");
        if (!snapshot.businessAdmin() || !snapshot.hasPermission("auth.users.manage", clock.instant()))
            throw error(HttpStatus.FORBIDDEN, "Platform administrator and management permission required");
        return snapshot;
    }

    private static OrgUnit readUnit(ResultSet rs) throws SQLException {
        long parent = rs.getLong("parent_id");
        Long parentId = rs.wasNull() ? null : parent;
        long store = rs.getLong("store_business_id");
        Long storeBusinessId = rs.wasNull() ? null : store;
        return new OrgUnit(rs.getLong("id"), rs.getString("org_code"), rs.getString("org_name"),
                rs.getString("org_type"), parentId, rs.getString("status"), storeBusinessId,
                rs.getLong("revision"));
    }

    private static java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static ResponseStatusException error(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }

    public record CreateOrgUnit(String code, String name, String type, Long parentId) { }
    public record OrgUnit(long id, String code, String name, String type, Long parentId, String status,
                          Long storeBusinessId, long revision) {
        public OrgUnit(long id, String code, String name, String type, Long parentId, String status) {
            this(id, code, name, type, parentId, status, null, 1);
        }
    }
    public record MembershipRequest(Long userId, Boolean primary, java.time.Instant validFrom,
                                    java.time.Instant validUntil) { }
    public record Member(long userId, long orgId, boolean primary, java.time.Instant validFrom,
                         java.time.Instant validUntil, boolean effective) {
        public Member(long userId, long orgId, boolean primary) {
            this(userId, orgId, primary, null, null, true);
        }
    }
    public record MemberChange(long userId, long orgId, String eventId, long permissionRevision) { }
    public record StoreMappingChange(long orgId, Long storeBusinessId, String eventId,
                                     List<AuthorizationRepository.UserVersion> versions) {
        public StoreMappingChange { versions = List.copyOf(versions); }
    }
    private record ActiveMembership(long id, Timestamp validFrom) { }
    private record StoreMappingRow(long id, String type, Long storeBusinessId, String status) { }
}
