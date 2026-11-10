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
                "SELECT id,org_code,org_name,org_type,parent_id,status FROM auth_org_unit ORDER BY id",
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

    /** Initial membership records do not confer store scope until the store mapping is verified. */
    public MemberChange addPrimaryMember(String token, long orgId, long userId) {
        return authenticated(token, actor -> {
            if (orgId <= 0 || userId <= 0 || repository.jdbc().queryForObject(
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
            if (repository.jdbc().queryForObject("SELECT COUNT(*) FROM auth_org_membership "
                    + "WHERE user_id=? AND status='ACTIVE' AND is_primary=1", Integer.class, userId) != 0) {
                throw error(HttpStatus.CONFLICT, "Primary membership already exists");
            }
            // A legacy STORE grant cannot be safely reconciled without verified mapping evidence.
            if (repository.jdbc().queryForObject("SELECT COUNT(*) FROM auth_user_store_identity WHERE user_id=?",
                    Integer.class, userId) != 0) {
                throw error(HttpStatus.CONFLICT, "Existing store scope requires verified migration");
            }
            var result = repository.mutateUsers(List.of(userId), "ORG_MEMBERSHIP_ADDED", false, jdbc ->
                    jdbc.update("INSERT INTO auth_org_membership(user_id,org_id,is_primary,status,valid_from) "
                                    + "VALUES (?,?,1,'ACTIVE',?)", userId, orgId, Timestamp.from(clock.instant())));
            repository.jdbc().update("INSERT INTO auth_authorization_audit"
                            + "(audit_id,actor_id,actor_revision,action,target_type,target_id,event_id) VALUES (?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), actor.userId(), actor.permissionRevision(),
                    "ORG_MEMBER_ADDED", "USER", userId, result.eventId());
            return new MemberChange(userId, orgId, result.eventId(), result.versions().get(0).permissionRevision());
        });
    }

    public List<Member> members(String token, long orgId) {
        return authenticated(token, actor -> {
            if (orgId <= 0 || repository.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM auth_org_unit WHERE id=? AND status='ACTIVE'", Integer.class, orgId) != 1) {
                throw error(HttpStatus.NOT_FOUND, "Active organization not found");
            }
            return repository.jdbc().query("SELECT user_id,org_id,is_primary FROM auth_org_membership "
                            + "WHERE org_id=? AND status='ACTIVE' AND (valid_from IS NULL OR valid_from<=?) "
                            + "AND (valid_until IS NULL OR valid_until>?) ORDER BY user_id",
                    (rs, row) -> new Member(rs.getLong(1), rs.getLong(2), rs.getInt(3) == 1),
                    orgId, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
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
        if (!snapshot.hasPermission("auth.users.manage", clock.instant()))
            throw error(HttpStatus.FORBIDDEN, "Management permission required");
        return snapshot;
    }

    private static OrgUnit readUnit(ResultSet rs) throws SQLException {
        long parent = rs.getLong("parent_id");
        Long parentId = rs.wasNull() ? null : parent;
        return new OrgUnit(rs.getLong("id"), rs.getString("org_code"), rs.getString("org_name"),
                rs.getString("org_type"), parentId, rs.getString("status"));
    }

    private static ResponseStatusException error(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }

    public record CreateOrgUnit(String code, String name, String type, Long parentId) { }
    public record OrgUnit(long id, String code, String name, String type, Long parentId, String status) { }
    public record Member(long userId, long orgId, boolean primary) { }
    public record MemberChange(long userId, long orgId, String eventId, long permissionRevision) { }
}
