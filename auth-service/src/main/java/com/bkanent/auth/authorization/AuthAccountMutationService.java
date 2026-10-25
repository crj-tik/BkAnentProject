package com.bkanent.auth.authorization;

import com.bkanent.auth.dto.AuthUserManagementModels.CreateUserRequest;
import com.bkanent.auth.dto.AuthUserManagementModels.UpdateUserRequest;
import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.auth.service.AuthTokenService;
import com.bkanent.auth.service.UserManagementException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Compatibility account mutations use the same authoritative writer as the new role APIs. */
@Service
@ConditionalOnProperty(prefix = "auth.authorization", name = "enabled", havingValue = "true")
public class AuthAccountMutationService {
    private final AuthorizationRepository repository;
    private final PasswordEncoder passwords;
    private final AuthTokenService tokens;

    @Autowired
    public AuthAccountMutationService(AuthorizationRepository repository, PasswordEncoder passwords, AuthTokenService tokens) {
        this.repository = repository;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    @jakarta.annotation.PostConstruct
    public void validateSchema() {
        repository.validateAccountEventSchema();
    }

    public EffectivePermissionService.Snapshot snapshot(long userId) {
        return repository.snapshot(userId, Instant.now()).orElseThrow(() -> error(HttpStatus.UNAUTHORIZED,
                "AUTH_UNAUTHORIZED", "账号授权状态缺失"));
    }

    public boolean active(long userId) {
        return repository.snapshot(userId, Instant.now()).map(s -> "ACTIVE".equals(s.status())).orElse(false);
    }

    public List<String> roles(long userId) {
        return authorizationInfo(userId).roles();
    }

    public AuthorizationInfo authorizationInfo(long userId) {
        Instant now = Instant.now();
        var facts = repository.loadFacts(userId, now).orElseThrow(() -> error(HttpStatus.UNAUTHORIZED,
                "AUTH_UNAUTHORIZED", "账号授权状态缺失"));
        var snapshot = new EffectivePermissionService().calculate(facts, now);
        var roles = facts.grants().stream()
                .filter(g -> g.grantActive() && g.roleActive() && !g.roleDeleted())
                .filter(g -> g.validFrom() == null || !g.validFrom().isAfter(now))
                .filter(g -> g.validUntil() == null || now.isBefore(g.validUntil()))
                .map(AuthorizationFacts.RoleGrant::roleCode).distinct().sorted().toList();
        return new AuthorizationInfo(snapshot, roles);
    }

    public record AuthorizationInfo(EffectivePermissionService.Snapshot snapshot, List<String> roles) {
        public AuthorizationInfo { roles = List.copyOf(roles); }
    }

    public UserAccountEntity create(long actorId, CreateUserRequest request) {
        throw credentialRequired();
    }

    public UserAccountEntity create(String accessToken, long actorId, CreateUserRequest request) {
        String hash = passwords.encode(request.password()); // No expensive hashing while holding the writer.
        return repository.withWriterLock(() -> {
            requireAdmin(accessToken, actorId);
            long role = roleId(request.roleCode());
            var key = new GeneratedKeyHolder();
            var result = repository.createAccount(jdbc -> jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO user_account(username,password_hash,display_name,role_code,tenant_code,account_status,business_admin,deleted)
                        VALUES (?,?,?,?,?,'ACTIVE',0,0)
                        """, new String[]{"id"});
                statement.setString(1, request.username().trim()); statement.setString(2, hash);
                statement.setString(3, request.displayName().trim()); statement.setString(4, normalizeRole(request.roleCode()));
                statement.setString(5, request.tenantCode().trim()); return statement;
            }, key), () -> java.util.Objects.requireNonNull(key.getKey()).longValue());
            long id = java.util.Objects.requireNonNull(key.getKey()).longValue();
            repository.replaceAccountRole(id, role, Instant.now());
            audit(actorId, id, result, "ACCOUNT_CREATED");
            return account(id);
        });
    }

    public UserAccountEntity update(long actorId, long userId, UpdateUserRequest request) {
        throw credentialRequired();
    }

    public UserAccountEntity update(String accessToken, long actorId, long userId, UpdateUserRequest request) {
        return repository.withWriterLock(() -> {
            requireAdmin(accessToken, actorId);
            UserAccountEntity old = account(userId);
            long role = roleId(request.roleCode());
            boolean disabling = !"ACTIVE".equals(request.accountStatus());
            if (actorId == userId && disabling) throw error(HttpStatus.FORBIDDEN, "AUTH_FORBIDDEN", "不能停用当前登录账号");
            if (disabling) ensureAnotherAdmin(userId);
            var result = repository.mutateUsers(List.of(userId), "ACCOUNT_UPDATED", disabling
                    && "ACTIVE".equals(old.getAccountStatus()), jdbc -> {
                jdbc.update("""
                        UPDATE user_account SET display_name=?,role_code=?,tenant_code=?,account_status=?,updated_at=CURRENT_TIMESTAMP
                        WHERE id=? AND deleted=0
                        """, request.displayName().trim(), normalizeRole(request.roleCode()), request.tenantCode().trim(), request.accountStatus(), userId);
                repository.replaceAccountRole(userId, role, Instant.now());
            });
            audit(actorId, userId, result, "ACCOUNT_UPDATED");
            return account(userId);
        });
    }

    public void resetPassword(long actorId, long userId, String password) {
        throw credentialRequired();
    }

    public void resetPassword(String accessToken, long actorId, long userId, String password) {
        String hash = passwords.encode(password);
        repository.withWriterLock(() -> {
            requireAdmin(accessToken, actorId);
            account(userId);
            var result = repository.mutateUsers(List.of(userId), "PASSWORD_RESET", true, jdbc -> jdbc.update(
                    "UPDATE user_account SET password_hash=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND deleted=0", hash, userId));
            audit(actorId, userId, result, "PASSWORD_RESET");
            return null;
        });
    }

    public void delete(long actorId, long userId) {
        throw credentialRequired();
    }

    public void delete(String accessToken, long actorId, long userId) {
        repository.withWriterLock(() -> {
            requireAdmin(accessToken, actorId);
            account(userId);
            if (actorId == userId) throw error(HttpStatus.FORBIDDEN, "AUTH_FORBIDDEN", "不能删除当前登录账号");
            ensureAnotherAdmin(userId);
            var result = repository.mutateUsers(List.of(userId), "ACCOUNT_DELETED", true, jdbc -> jdbc.update(
                    "UPDATE user_account SET deleted=1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND deleted=0", userId));
            audit(actorId, userId, result, "ACCOUNT_DELETED");
            return null;
        });
    }

    public void logout(String token) {
        var principal = tokens.verifyForLogout(token);
        if (principal == null) return; // No principal is returned or created by this special-purpose verification.
        String digest = AuthTokenService.logoutDigest(token);
        var event = repository.recordLogout(principal.userId(), digest, Instant.ofEpochSecond(principal.expiresAt()));
        if ("REVOKED".equals(event.state())) return;
        try {
            // SQL denies re-entry before Redis. Redis failure leaves durable PENDING for exact digest retry.
            tokens.revoke(token);
            repository.completeLogout(digest, event.eventId());
        } catch (RuntimeException failure) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_LOGOUT_PENDING", "注销已受理，令牌吊销待重试");
        }
    }

    private UserAccountEntity account(long id) {
        var users = repository.jdbc().query("SELECT * FROM user_account WHERE id=? AND deleted=0", (rs, row) -> {
            var user = new UserAccountEntity(); user.setId(rs.getLong("id")); user.setUsername(rs.getString("username"));
            user.setDisplayName(rs.getString("display_name")); user.setRoleCode(rs.getString("role_code"));
            user.setTenantCode(rs.getString("tenant_code")); user.setAccountStatus(rs.getString("account_status"));
            user.setPasswordHash(rs.getString("password_hash")); user.setDeleted(rs.getInt("deleted"));
            user.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            user.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime()); return user;
        }, id);
        if (users.isEmpty()) throw error(HttpStatus.NOT_FOUND, "AUTH_USER_NOT_FOUND", "用户不存在");
        return users.get(0);
    }

    private long roleId(String code) {
        var roles = repository.jdbc().queryForList(
                "SELECT id FROM auth_role WHERE role_code=? AND active=1 AND assignable=1 AND deleted=0", Long.class, normalizeRole(code));
        if (roles.size() != 1) throw error(HttpStatus.BAD_REQUEST, "AUTH_ROLE_INVALID", "角色不存在或不可分配");
        return roles.get(0);
    }

    private static String normalizeRole(String code) { return code.trim().toUpperCase(Locale.ROOT); }

    private static UserManagementException credentialRequired() {
        return error(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHORIZED", "账号写入需要有效访问凭据");
    }

    /** Runs after password hashing and under the writer mutex, never trusting actorId alone. */
    private void requireAdmin(String accessToken, long actor) {
        var principal = tokens.parse(accessToken);
        if (principal == null || !"access".equals(principal.tokenType()) || principal.userId() != actor)
            throw credentialRequired();
        var authorization = snapshot(actor);
        if (principal.expiresAt() <= Instant.now().getEpochSecond()) throw credentialRequired();
        if (!authorization.businessAdmin() || !authorization.hasPermission("auth.users.manage", Instant.now()))
            throw error(HttpStatus.FORBIDDEN, "AUTH_FORBIDDEN", "需要业务管理员权限");
    }

    private void ensureAnotherAdmin(long target) {
        int admins = repository.jdbc().queryForObject("""
                SELECT COUNT(*) FROM user_account u JOIN auth_user_authorization_state s ON s.user_id=u.id
                WHERE u.business_admin=1 AND u.deleted=0 AND u.account_status='ACTIVE' AND s.status='ACTIVE' AND u.id<>?
                """, Integer.class, target);
        boolean targetAdmin = repository.snapshot(target, Instant.now()).map(EffectivePermissionService.Snapshot::businessAdmin).orElse(false);
        if (targetAdmin && admins == 0) throw error(HttpStatus.CONFLICT, "AUTH_USER_CONFLICT", "系统至少需要保留一个启用的业务管理员");
    }

    private void audit(long actor, long target, AuthorizationRepository.MutationResult result, String action) {
        long revision = repository.currentVersion(actor).orElseThrow().permissionRevision();
        repository.jdbc().update("""
                INSERT INTO auth_authorization_audit(audit_id,actor_id,actor_revision,action,target_type,target_id,event_id)
                VALUES (?,?,?,?,'USER',?,?)
                """, UUID.randomUUID().toString(), actor, revision, action, target, result.eventId());
    }

    private static UserManagementException error(HttpStatus status, String code, String message) {
        return new UserManagementException(status, code, message);
    }
}
