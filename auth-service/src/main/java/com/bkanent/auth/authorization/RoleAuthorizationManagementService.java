package com.bkanent.auth.authorization;

import com.bkanent.auth.service.AuthTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/** Authenticated administrative entry. Never accepts an actor/admin claim from the request body. */
@Service
@ConditionalOnProperty(prefix = "auth.authorization", name = "enabled", havingValue = "true")
public class RoleAuthorizationManagementService {
    private final AuthTokenService tokens;
    private final AuthorizationRepository repository;
    private final Clock clock;

    @Autowired
    public RoleAuthorizationManagementService(AuthTokenService tokens, AuthorizationRepository repository) {
        this(tokens, repository, Clock.systemUTC());
    }

    public RoleAuthorizationManagementService(AuthTokenService tokens, AuthorizationRepository repository, Clock clock) {
        this.tokens = tokens;
        this.repository = repository;
        this.clock = clock;
    }

    public List<AuthorizationFacts.Permission> catalog(String accessToken) {
        return authenticated(accessToken, "auth.roles.manage", actor -> repository.loadFacts(actor.userId(), clock.instant())
                .orElseThrow().catalog());
    }

    public List<Role> roles(String accessToken) {
        return authenticated(accessToken, "auth.roles.manage", actor -> repository.jdbc().query(
                "SELECT id,role_code,role_name,description,active,assignable FROM auth_role WHERE deleted=0 ORDER BY id LIMIT 1000",
                (rs, row) -> role(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)==1, rs.getInt(6)==1)));
    }

    public ManagementResult createRole(String accessToken, RoleDefinition definition) {
        return authenticated(accessToken, "auth.roles.manage", actor -> {
            validateDefinition(definition);
            ensureCatalog(definition.permissionCodes());
            if (repository.jdbc().queryForObject("SELECT COUNT(*) FROM auth_role WHERE role_code=?", Integer.class,
                    definition.code()) != 0) throw error(HttpStatus.CONFLICT, "Role code already exists");
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            var result = repository.mutateRole(-1, "ROLE_CREATED", jdbc -> {
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement("INSERT INTO auth_role(role_code,role_name,description,active,assignable) VALUES (?,?,?,?,?)", new String[]{"id"});
                    statement.setString(1, definition.code()); statement.setString(2, definition.name());
                    statement.setString(3, definition.description()); statement.setInt(4, definition.active()?1:0);
                    statement.setInt(5, definition.assignable()?1:0);
                    return statement;
                }, key);
                replacePermissions(Objects.requireNonNull(key.getKey()).longValue(), definition.permissionCodes());
            });
            long roleId = Objects.requireNonNull(key.getKey()).longValue();
            audit(actor, "ROLE_CREATED", "ROLE", roleId, result);
            return new ManagementResult(roleId, result.eventId(), result.versions(), false);
        });
    }

    public ManagementResult updateRole(String accessToken, long roleId, RoleDefinition definition) {
        return authenticated(accessToken, "auth.roles.manage", actor -> {
            validateDefinition(definition);
            Role existing = requireRole(roleId, false);
            if (!existing.code().equals(definition.code())) throw error(HttpStatus.BAD_REQUEST, "Role code is immutable");
            ensureCatalog(definition.permissionCodes());
            var result = repository.mutateRole(roleId, "ROLE_UPDATED", jdbc -> {
                jdbc.update("UPDATE auth_role SET role_name=?,description=?,active=?,assignable=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND deleted=0",
                        definition.name(), definition.description(), definition.active()?1:0, definition.assignable()?1:0, roleId);
                replacePermissions(roleId, definition.permissionCodes());
            });
            audit(actor, "ROLE_UPDATED", "ROLE", roleId, result);
            return new ManagementResult(roleId, result.eventId(), result.versions(), false);
        });
    }

    public ManagementResult deleteRole(String accessToken, long roleId) {
        return authenticated(accessToken, "auth.roles.manage", actor -> {
            requireRole(roleId, false);
            var result = repository.mutateRole(roleId, "ROLE_DELETED", jdbc -> jdbc.update(
                    "UPDATE auth_role SET active=0,assignable=0,deleted=1,updated_at=CURRENT_TIMESTAMP WHERE id=?", roleId));
            audit(actor, "ROLE_DELETED", "ROLE", roleId, result);
            return new ManagementResult(roleId, result.eventId(), result.versions(), false);
        });
    }

    public List<UserRoleGrant> userRoles(String accessToken, long userId) {
        return authenticated(accessToken, "auth.users.manage", actor -> {
            requireActiveUser(userId);
            return repository.jdbc().query("SELECT role_id,valid_from,valid_until FROM auth_user_role WHERE user_id=? AND active=1 ORDER BY role_id",
                    (rs, row) -> new UserRoleGrant(rs.getLong(1), instant(rs.getTimestamp(2)), instant(rs.getTimestamp(3))), userId);
        });
    }

    public ManagementResult replaceUserRoles(String accessToken, long userId, List<UserRoleGrant> grants) {
        final List<UserRoleGrant> requested = grants == null ? null : new java.util.ArrayList<>(grants);
        return authenticated(accessToken, "auth.users.manage", actor -> {
            requireActiveUser(userId);
            if (requested == null || requested.size() > 100) throw error(HttpStatus.BAD_REQUEST, "Invalid grant count");
            var unique = new TreeSet<Long>();
            Instant now = clock.instant();
            for (UserRoleGrant grant : requested) {
                if (grant == null || !unique.add(grant.roleId())) throw error(HttpStatus.BAD_REQUEST, "Duplicate/invalid role");
                requireRole(grant.roleId(), true);
                if (grant.validFrom()!=null && grant.validUntil()!=null && !grant.validFrom().isBefore(grant.validUntil())
                        || grant.validUntil()!=null && !grant.validUntil().isAfter(now)) throw error(HttpStatus.BAD_REQUEST, "Invalid grant interval");
            }
            var result = repository.mutateUsers(List.of(userId), "USER_ROLES_REPLACED", false, jdbc -> {
                jdbc.update("DELETE FROM auth_user_role WHERE user_id=?", userId);
                for (UserRoleGrant grant : requested) jdbc.update("INSERT INTO auth_user_role(user_id,role_id,valid_from,valid_until,active) VALUES (?,?,?,?,1)",
                        userId, grant.roleId(), timestamp(grant.validFrom()), timestamp(grant.validUntil()));
                repository.resetGrantBoundaries(userId, now);
            });
            audit(actor, "USER_ROLES_REPLACED", "USER", userId, result);
            return new ManagementResult(userId, result.eventId(), result.versions(), false);
        });
    }

    public ManagementResult setBusinessAdmin(String accessToken, long userId, boolean enabled) {
        return authenticated(accessToken, "auth.users.manage", actor -> {
            // Delegated user management does not include granting the independent global-admin flag.
            if (!actor.businessAdmin()) throw error(HttpStatus.FORBIDDEN, "Business administrator required");
            requireActiveUser(userId);
            if (!enabled && actor.userId()==userId) throw error(HttpStatus.FORBIDDEN, "Cannot revoke own administrator flag");
            var result = repository.mutateUsers(List.of(userId), "BUSINESS_ADMIN_CHANGED", false, jdbc -> {
                jdbc.update("UPDATE user_account SET business_admin=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", enabled?1:0, userId);
                Integer admins = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM user_account u JOIN auth_user_authorization_state s ON s.user_id=u.id
                        WHERE u.business_admin=1 AND u.deleted=0 AND u.account_status='ACTIVE' AND s.status='ACTIVE'
                        """, Integer.class);
                if (admins == null || admins==0) throw error(HttpStatus.CONFLICT, "At least one active business administrator must remain");
            });
            audit(actor, "BUSINESS_ADMIN_CHANGED", "USER", userId, result);
            return new ManagementResult(userId, result.eventId(), result.versions(), false);
        });
    }

    public BusinessIdentityChange setBrokerIdentity(String accessToken, long userId, Long brokerId) {
        return authenticated(accessToken, "auth.business-identity.manage", actor -> {
            if (!actor.businessAdmin()) throw error(HttpStatus.FORBIDDEN, "Business administrator required");
            requireActiveUser(userId);
            if (brokerId != null && brokerId <= 0) throw error(HttpStatus.BAD_REQUEST, "Invalid broker id");
            List<Long> current = repository.jdbc().queryForList(
                    "SELECT identity_id FROM auth_user_business_identity WHERE user_id=? AND identity_type='BROKER'",
                    Long.class, userId);
            Long oldBrokerId = current.isEmpty() ? null : current.get(0);
            if (Objects.equals(oldBrokerId, brokerId)) {
                return new BusinessIdentityChange(userId, brokerId, null, List.of());
            }
            if (brokerId != null && repository.jdbc().queryForObject("""
                    SELECT COUNT(*) FROM auth_user_business_identity
                    WHERE identity_type='BROKER' AND identity_id=? AND user_id<>?
                    """, Integer.class, brokerId, userId) != 0) {
                throw error(HttpStatus.CONFLICT, "Broker identity is already assigned");
            }
            var result = repository.mutateUsers(List.of(userId), "BUSINESS_IDENTITY_CHANGED", false, jdbc -> {
                jdbc.update("DELETE FROM auth_user_business_identity WHERE user_id=? AND identity_type='BROKER'", userId);
                if (brokerId != null) jdbc.update("""
                        INSERT INTO auth_user_business_identity(user_id,identity_type,identity_id)
                        VALUES (?,'BROKER',?)
                        """, userId, brokerId);
            });
            if (oldBrokerId != null) auditIdentity(actor, userId, oldBrokerId, "BROKER_IDENTITY_REMOVED", result.eventId());
            if (brokerId != null) auditIdentity(actor, userId, brokerId, "BROKER_IDENTITY_ASSIGNED", result.eventId());
            return new BusinessIdentityChange(userId, brokerId, result.eventId(), result.versions());
        });
    }

    public List<BusinessIdentity> businessIdentities(String accessToken, long userId) {
        return authenticated(accessToken, "auth.business-identity.manage", actor -> {
            if (!actor.businessAdmin()) throw error(HttpStatus.FORBIDDEN, "Business administrator required");
            requireActiveUser(userId);
            return repository.jdbc().query("""
                    SELECT identity_type,identity_id FROM auth_user_business_identity
                    WHERE user_id=? ORDER BY identity_type
                    """, (rs, row) -> new BusinessIdentity(rs.getString(1), rs.getLong(2)), userId);
        });
    }

    private void auditIdentity(EffectivePermissionService.Snapshot actor, long userId, long brokerId,
                               String action, String eventId) {
        repository.jdbc().update("""
                INSERT INTO auth_authorization_audit(audit_id,actor_id,actor_revision,action,target_type,target_id,event_id)
                VALUES (?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), actor.userId(), actor.permissionRevision(), action, "BROKER", brokerId, eventId);
    }

    private <T> T authenticated(String accessToken, String permission, Function<EffectivePermissionService.Snapshot,T> work) {
        authenticate(accessToken, permission);
        return repository.withWriterLock(() -> work.apply(authenticate(accessToken, permission)));
    }

    private EffectivePermissionService.Snapshot authenticate(String accessToken, String permission) {
        var principal = tokens.parse(accessToken);
        if (principal==null || !"access".equals(principal.tokenType())) throw error(HttpStatus.UNAUTHORIZED, "Valid access token required");
        var snapshot = repository.snapshot(principal.userId(), clock.instant()).orElseThrow(() -> error(HttpStatus.UNAUTHORIZED, "Unknown authorization state"));
        if (!"ACTIVE".equals(snapshot.status())) throw error(HttpStatus.UNAUTHORIZED, "Inactive user");
        if (!snapshot.hasPermission(permission, clock.instant())) throw error(HttpStatus.FORBIDDEN, "Management permission required");
        return snapshot;
    }

    private void requireActiveUser(long userId) {
        if (userId<=0 || repository.jdbc().queryForObject("SELECT COUNT(*) FROM user_account WHERE id=? AND deleted=0 AND account_status='ACTIVE'", Integer.class, userId)!=1)
            throw error(HttpStatus.NOT_FOUND, "Active user not found");
    }

    private Role requireRole(long roleId, boolean assignable) {
        List<Role> roles = repository.jdbc().query("SELECT id,role_code,role_name,description,active,assignable FROM auth_role WHERE id=? AND deleted=0",
                (rs,row) -> role(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5)==1,rs.getInt(6)==1), roleId);
        if (roles.isEmpty()) throw error(HttpStatus.NOT_FOUND, "Role not found");
        Role role = roles.get(0);
        if (assignable && (!role.active() || !role.assignable())) throw error(HttpStatus.BAD_REQUEST, "Role not assignable");
        return role;
    }

    private Role role(long id, String code, String name, String description, boolean active, boolean assignable) {
        return new Role(id,code,name,description,active,assignable,repository.jdbc().queryForList(
                "SELECT permission_code FROM auth_role_permission WHERE role_id=? ORDER BY permission_code", String.class,id));
    }

    private void validateDefinition(RoleDefinition role) {
        if (role==null || role.code()==null || !role.code().matches("[A-Z][A-Z0-9_]{0,31}")
                || role.name()==null || role.name().isBlank() || role.name().length()>64
                || role.description()!=null && role.description().length()>255
                || role.permissionCodes()==null || role.permissionCodes().size()>500) throw error(HttpStatus.BAD_REQUEST, "Invalid role definition");
    }

    private void ensureCatalog(List<String> codes) {
        TreeSet<String> unique = new TreeSet<>();
        for (String code : codes) {
            if (code==null || !unique.add(code) || !code.matches("[a-z0-9][a-z0-9._-]{0,159}")
                    || repository.jdbc().queryForObject("SELECT COUNT(*) FROM auth_permission WHERE permission_code=? AND active=1", Integer.class,code)!=1)
                throw error(HttpStatus.BAD_REQUEST, "Permission is not registered and active");
        }
    }

    private void replacePermissions(long roleId, List<String> codes) {
        JdbcTemplate jdbc = repository.jdbc();
        jdbc.update("DELETE FROM auth_role_permission WHERE role_id=?",roleId);
        for (String code : codes) jdbc.update("INSERT INTO auth_role_permission(role_id,permission_code) VALUES (?,?)",roleId,code);
    }

    private void audit(EffectivePermissionService.Snapshot actor, String action, String type, long target,
                       AuthorizationRepository.MutationResult result) {
        repository.jdbc().update("INSERT INTO auth_authorization_audit(audit_id,actor_id,actor_revision,action,target_type,target_id,event_id) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),actor.userId(),actor.permissionRevision(),action,type,target,result.eventId());
    }

    private static ResponseStatusException error(HttpStatus status, String reason) { return new ResponseStatusException(status,reason); }
    private static Instant instant(Timestamp timestamp) { return timestamp==null?null:timestamp.toInstant(); }
    private static Timestamp timestamp(Instant instant) { return instant==null?null:Timestamp.from(instant); }

    public record RoleDefinition(String code,String name,String description,boolean active,boolean assignable,List<String> permissionCodes) {
        public RoleDefinition {
            permissionCodes = permissionCodes == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(permissionCodes));
        }
    }
    public record Role(long id,String code,String name,String description,boolean active,boolean assignable,List<String> permissionCodes) {
        public Role { permissionCodes = List.copyOf(permissionCodes); }
    }
    public record UserRoleGrant(long roleId,Instant validFrom,Instant validUntil) { }
    public record ManagementResult(long targetId,String eventId,List<AuthorizationRepository.UserVersion> versions,boolean publicationConfirmed) { }
    public record BusinessIdentity(String type, long identityId) { }
    public record BusinessIdentityChange(long userId, Long brokerId, String eventId,
                                         List<AuthorizationRepository.UserVersion> versions) {
        public BusinessIdentityChange { versions = List.copyOf(versions); }
    }
}
