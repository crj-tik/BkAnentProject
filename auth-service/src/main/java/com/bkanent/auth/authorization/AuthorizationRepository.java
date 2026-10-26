package com.bkanent.auth.authorization;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * JDBC authorization truth, not a user-facing management API. Callers must authenticate and authorize
 * before invoking write methods. Every writer takes the mutex, then user_account/state locks in
 * ascending userId order. Remote publication must never run inside a mutation callback.
 */
public final class AuthorizationRepository {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;
    private final EffectivePermissionService calculator = new EffectivePermissionService();

    public AuthorizationRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactionManager = Objects.requireNonNull(transactionManager);
    }

    public Optional<EffectivePermissionService.Snapshot> snapshot(long userId, Instant now) {
        return loadFacts(userId, now).map(facts -> calculator.calculate(facts, now));
    }

    public Optional<AuthorizationFacts> loadFacts(long userId, Instant now) {
        Objects.requireNonNull(now, "now");
        return withWriterLock(() -> {
            processUserGrantBoundaries(userId, now);
            return readFacts(userId, now);
        });
    }

    private Optional<AuthorizationFacts> readFacts(long userId, Instant now) {
        return transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).execute(status -> {
            List<AuthorizationFacts.User> users = jdbc.query("""
                    SELECT u.id, u.account_status, u.deleted, u.business_admin,
                           s.permission_revision, s.credential_epoch, s.status
                    FROM user_account u JOIN auth_user_authorization_state s ON s.user_id = u.id
                    WHERE u.id = ?
                    """, (rs, row) -> new AuthorizationFacts.User(rs.getLong("id"),
                    "ACTIVE".equals(rs.getString("account_status")), rs.getInt("deleted") != 0,
                    rs.getInt("business_admin") == 1, rs.getLong("permission_revision"),
                    rs.getLong("credential_epoch"), rs.getString("status")), userId);
            if (users.isEmpty()) {
                return Optional.empty();
            }
            List<AuthorizationFacts.Permission> catalog = jdbc.query("""
                    SELECT permission_code, domain, operation, active, version
                    FROM auth_permission ORDER BY permission_code
                    """, (rs, row) -> new AuthorizationFacts.Permission(rs.getString("permission_code"),
                    rs.getString("domain"), rs.getString("operation"), rs.getInt("active") == 1,
                    rs.getLong("version")));
            List<AuthorizationFacts.RoleGrant> grants = jdbc.query("""
                    SELECT r.id, r.role_code, ur.valid_from, ur.valid_until
                    FROM auth_user_role ur JOIN auth_role r ON r.id = ur.role_id
                    WHERE ur.user_id = ? AND ur.active = 1 AND r.active = 1 AND r.deleted = 0
                      AND (ur.valid_until IS NULL OR ur.valid_until > ?)
                      AND (ur.valid_from IS NULL OR ur.valid_until IS NULL OR ur.valid_from < ur.valid_until)
                    ORDER BY r.id
                    """, (rs, row) -> {
                long roleId = rs.getLong("id");
                return new AuthorizationFacts.RoleGrant(roleId, rs.getString("role_code"), true, false,
                        true, instant(rs.getTimestamp("valid_from")), instant(rs.getTimestamp("valid_until")),
                        new TreeSet<>(jdbc.queryForList("""
                                SELECT rp.permission_code FROM auth_role_permission rp
                                JOIN auth_permission p ON p.permission_code = rp.permission_code
                                WHERE rp.role_id = ? AND p.active = 1
                                ORDER BY rp.permission_code
                                """, String.class, roleId)));
            }, userId, timestamp(now));
            return Optional.of(new AuthorizationFacts(users.get(0), catalog, grants));
        });
    }

    /** Atomic versions/outbox for account, admin or user-role changes; no implicit API authorization. */
    public MutationResult mutateUsers(Collection<Long> userIds, String reason, boolean invalidateCredentials,
                                      Consumer<JdbcTemplate> mutation) {
        return mutate(() -> userIds, reason, invalidateCredentials, mutation);
    }

    /** Includes all associated users, even grants that are currently inactive/future/expired. */
    public MutationResult mutateRole(long roleId, String reason, Consumer<JdbcTemplate> mutation) {
        return mutate(() -> jdbc.queryForList("SELECT user_id FROM auth_user_role WHERE role_id = ?",
                Long.class, roleId), reason, false, mutation);
    }

    /**
     * Permission directory changes affect associated users and administrators, and also every other
     * snapshot's global catalogVersion. Conservatively version all users so same-revision rebuilds
     * never silently change stable content. Role-permission association writes use mutateRole.
     */
    public MutationResult mutatePermission(String code, String reason, Consumer<JdbcTemplate> mutation) {
        Objects.requireNonNull(code, "code");
        return mutateCatalog(reason, mutation);
    }

    /** Catalog additions/removals can change the catalog version in every user's stable snapshot. */
    public MutationResult mutateCatalog(String reason, Consumer<JdbcTemplate> mutation) {
        return mutate(() -> jdbc.queryForList("SELECT id FROM user_account", Long.class),
                reason, false, mutation);
    }

    private MutationResult mutate(Supplier<Collection<Long>> affected, String reason,
                                  boolean invalidateCredentials, Consumer<JdbcTemplate> mutation) {
        requireReason(reason);
        Objects.requireNonNull(mutation, "mutation");
        return transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).execute(status -> {
            lockWriter();
            TreeSet<Long> users = new TreeSet<>(affected.get());
            for (Long userId : users) {
                if (userId == null || userId <= 0) {
                    throw new IllegalArgumentException("Invalid affected user");
                }
                List<Long> existing = jdbc.queryForList("SELECT id FROM user_account WHERE id = ? FOR UPDATE",
                        Long.class, userId);
                if (existing.isEmpty()) {
                    throw new IllegalArgumentException("Affected user does not exist: " + userId);
                }
                if (jdbc.queryForList("SELECT user_id FROM auth_user_authorization_state WHERE user_id = ? FOR UPDATE",
                        Long.class, userId).size() != 1) {
                    throw new IllegalStateException("Authorization state missing; migration required");
                }
            }
            mutation.accept(jdbc);
            List<UserVersion> versions = new ArrayList<>();
            for (Long userId : users) {
                String accountStatus = jdbc.queryForObject("""
                        SELECT CASE WHEN deleted <> 0 THEN 'DELETED'
                            WHEN account_status = 'ACTIVE' THEN 'ACTIVE' ELSE 'DISABLED' END
                        FROM user_account WHERE id = ?
                        """, String.class, userId);
                if (jdbc.update("""
                        UPDATE auth_user_authorization_state
                        SET permission_revision = permission_revision + 1,
                            credential_epoch = credential_epoch + ?, status = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE user_id = ?
                        """, invalidateCredentials ? 1 : 0, accountStatus, userId) != 1) {
                    throw new IllegalStateException("Missing authorization state");
                }
                versions.add(readVersion(userId));
            }
            String eventId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO auth_authorization_outbox (event_id, affected_users, revisions, reason)
                    VALUES (?, ?, ?, ?)
                    """, eventId, users.toString(), versionsJson(versions), reason);
            return new MutationResult(eventId, versions);
        });
    }

    /** New accounts must be inserted explicitly; never repair missing migrated state on a write. */
    public MutationResult createAccount(Consumer<JdbcTemplate> insert, Supplier<Long> insertedId) {
        return withWriterLock(() -> {
            insert.accept(jdbc);
            long id = insertedId.get();
            jdbc.update("INSERT INTO auth_user_authorization_state(user_id,status) VALUES (?,'ACTIVE')", id);
            return mutateUsers(List.of(id), "ACCOUNT_CREATED", false, ignored -> { });
        });
    }

    public void replaceAccountRole(long userId, long roleId, Instant now) {
        jdbc.update("DELETE FROM auth_user_role WHERE user_id=?", userId);
        jdbc.update("INSERT INTO auth_user_role(user_id,role_id,active) VALUES (?,?,1)", userId, roleId);
        resetGrantBoundaries(userId, now);
    }

    /** SQL acceptance precedes external revocation: pending events already deny token re-entry. */
    public LogoutEvent recordLogout(long userId, String tokenDigest, Instant expiresAt) {
        requireTokenDigest(tokenDigest);
        return withWriterLock(() -> {
            Optional<LogoutEvent> existing = logoutEvent(tokenDigest);
            if (existing.isPresent()) {
                if (existing.get().userId() != userId) throw new IllegalStateException("Logout actor mismatch");
                return existing.get();
            }
            MutationResult result = mutateUsers(List.of(userId), "LOGOUT", true, ignored -> { });
            jdbc.update("""
                    INSERT INTO auth_account_logout_event(token_digest,user_id,event_id,expires_at,state)
                    VALUES (?,?,?,?,'PENDING')
                    """, tokenDigest, userId, result.eventId(), timestamp(expiresAt));
            return new LogoutEvent(userId, result.eventId(), "PENDING");
        });
    }

    public boolean isLogoutRecorded(String tokenDigest) {
        return logoutEvent(tokenDigest).isPresent();
    }

    public Optional<LogoutEvent> logoutEvent(String tokenDigest) {
        requireTokenDigest(tokenDigest);
        return jdbc.query("SELECT user_id,event_id,state FROM auth_account_logout_event WHERE token_digest=?",
                (rs, row) -> new LogoutEvent(rs.getLong(1), rs.getString(2), rs.getString(3)), tokenDigest)
                .stream().findFirst();
    }

    public void completeLogout(String tokenDigest, String eventId) {
        if (jdbc.update("""
                UPDATE auth_account_logout_event SET state='REVOKED',updated_at=CURRENT_TIMESTAMP
                WHERE token_digest=? AND event_id=? AND state IN ('PENDING','REVOKED')
                """, tokenDigest, eventId) != 1) throw new IllegalStateException("Logout completion missing");
    }

    public void validateAccountEventSchema() {
        withWriterLock(() -> {
            jdbc.queryForList("SELECT token_digest,user_id,event_id,state,expires_at FROM auth_account_logout_event WHERE 1=0");
            jdbc.queryForList("SELECT permission_revision,credential_epoch,status FROM auth_user_authorization_state WHERE 1=0");
            jdbc.queryForList("SELECT business_admin FROM user_account WHERE 1=0");
            jdbc.queryForList("SELECT event_id,lease_epoch,status FROM auth_authorization_outbox WHERE 1=0");
            jdbc.queryForList("SELECT role_id,valid_from,valid_until,active FROM auth_user_role WHERE 1=0");
            return null;
        });
    }

    private static void requireTokenDigest(String digest) {
        if (digest == null || !digest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid token digest");
    }

    public record LogoutEvent(long userId, String eventId, String state) { }

    public boolean outboxLeaseValid(String eventId, long leaseEpoch, Instant now) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM auth_authorization_outbox
                WHERE event_id=? AND lease_epoch=? AND status='CLAIMED' AND lease_until>?
                """, Integer.class, eventId, leaseEpoch, timestamp(now)) == 1;
    }

    public boolean failOutbox(String eventId, long leaseEpoch, Instant now) {
        return jdbc.update("""
                UPDATE auth_authorization_outbox SET status='FAILED',lease_until=NULL
                WHERE event_id=? AND lease_epoch=? AND status='CLAIMED' AND lease_until>?
                """, eventId, leaseEpoch, timestamp(now)) == 1;
    }

    /** Exact JSON array member matching; user 1 never matches user 11. FAILED blocks renewal. */
    public boolean pendingUserEvents(long userId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM auth_authorization_outbox WHERE status IN ('PENDING','CLAIMED','FAILED')
                AND CONCAT(',',REPLACE(REPLACE(REPLACE(affected_users,' ',''),'[',''),']',''),',') LIKE ?
                """, Integer.class, "%," + userId + ",%") > 0;
    }

    public boolean publicationCurrent(long userId, long revision, long credentialEpoch, String accountStatus,
                                      String eventId, long leaseEpoch, Instant now) {
        return withWriterLock(() -> {
            processUserGrantBoundaries(userId, now);
            return versionMatches(userId, revision, credentialEpoch, accountStatus)
                    && outboxLeaseValid(eventId, leaseEpoch, now);
        });
    }

    public boolean renewalCurrent(long userId, long revision, long credentialEpoch, String accountStatus, Instant now) {
        return withWriterLock(() -> {
            processUserGrantBoundaries(userId, now);
            return versionMatches(userId, revision, credentialEpoch, accountStatus) && !pendingUserEvents(userId);
        });
    }

    private boolean versionMatches(long userId, long revision, long epoch, String status) {
        return currentVersion(userId).map(v -> v.permissionRevision()==revision && v.credentialEpoch()==epoch
                && v.status().equals(status)).orElse(false);
    }

    public Optional<UserVersion> currentVersion(long userId) {
        List<UserVersion> versions = jdbc.query("""
                SELECT user_id, permission_revision, credential_epoch, status
                FROM auth_user_authorization_state WHERE user_id = ?
                """, (rs, row) -> new UserVersion(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)), userId);
        return versions.stream().findFirst();
    }

    /** Claims are bounded; old workers cannot complete or retry a reclaimed event. */
    public List<OutboxClaim> claimOutbox(int limit, Instant now, Duration lease) {
        if (limit < 1 || limit > 100 || lease.isNegative() || lease.isZero()
                || lease.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Invalid bounded claim");
        }
        return transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).execute(status -> {
            List<OutboxClaim> candidates = jdbc.query("""
                    SELECT event_id, affected_users, revisions, reason, attempts, lease_epoch
                    FROM auth_authorization_outbox
                    WHERE (status = 'PENDING' AND next_attempt_at <= ?)
                       OR (status = 'CLAIMED' AND lease_until <= ?)
                    ORDER BY committed_at, event_id LIMIT ? FOR UPDATE SKIP LOCKED
                    """, (rs, row) -> new OutboxClaim(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getInt(5) + 1, rs.getLong(6) + 1, now.plus(lease)),
                    timestamp(now), timestamp(now), limit);
            List<OutboxClaim> claimed = new ArrayList<>();
            for (OutboxClaim claim : candidates) {
                int updated = jdbc.update("""
                        UPDATE auth_authorization_outbox SET status = 'CLAIMED',
                            attempts = attempts + 1, lease_epoch = lease_epoch + 1, lease_until = ?
                        WHERE event_id = ? AND lease_epoch = ? AND
                            ((status = 'PENDING' AND next_attempt_at <= ?)
                             OR (status = 'CLAIMED' AND lease_until <= ?))
                        """, timestamp(claim.leaseUntil()), claim.eventId(), claim.leaseEpoch() - 1,
                        timestamp(now), timestamp(now));
                if (updated == 1) {
                    claimed.add(claim);
                }
            }
            return List.copyOf(claimed);
        });
    }

    public boolean completeOutbox(String eventId, long leaseEpoch, Instant now) {
        return jdbc.update("""
                UPDATE auth_authorization_outbox SET status = 'PUBLISHED', lease_until = NULL
                WHERE event_id = ? AND lease_epoch = ? AND status = 'CLAIMED' AND lease_until > ?
                """, eventId, leaseEpoch, timestamp(now)) == 1;
    }

    public boolean retryOutbox(String eventId, long leaseEpoch, Instant now, Instant nextAttemptAt) {
        if (nextAttemptAt.isBefore(now)) {
            throw new IllegalArgumentException("Retry time before now");
        }
        return jdbc.update("""
                UPDATE auth_authorization_outbox SET status = 'PENDING', lease_until = NULL, next_attempt_at = ?
                WHERE event_id = ? AND lease_epoch = ? AND status = 'CLAIMED' AND lease_until > ?
                """, timestamp(nextAttemptAt), eventId, leaseEpoch, timestamp(now)) == 1;
    }

    /**
     * Records one execution tuple per verified ingress, not an authentication credential or an owner check.
     * The provider verifies signatures/ACL/scope and supplies hashes plus the original absolute deadlines.
     * Retries use a new nonce, but must preserve every recorded field, including both deadlines.
     *
     * Current account/state and grant boundaries are checked under the same mutex as all authorization
     * writers. No binding write changes a revision, credential epoch or outbox. A due grant boundary may
     * independently version authorization; the stale expected revision then conflicts. Providers should
     * wrap nonce consumption, this call and signing in withWriterLock and throw on CONFLICT/signing failure
     * to roll back that entire admission. No remote I/O belongs inside that transaction.
     */
    public BindingResult bindIngressExecution(String ingressHash, String tupleHash, long actorId, String caller,
                                               String runId, String sessionId, String policyDigest,
                                               String operationsHash, String executionHash,
                                               long expiresAtEpochSecond, long hardDeadlineEpochSecond,
                                               long expectedPermissionRevision, long expectedCredentialEpoch) {
        requireExecutionDigest(ingressHash);
        requireExecutionDigest(tupleHash);
        requireExecutionDigest(policyDigest);
        requireExecutionDigest(operationsHash);
        requireExecutionDigest(executionHash);
        if (actorId <= 0 || caller == null || !caller.matches("[a-z][a-z0-9-]{0,95}")
                || expectedPermissionRevision <= 0 || expectedCredentialEpoch < 0
                || expiresAtEpochSecond <= 0 || hardDeadlineEpochSecond < expiresAtEpochSecond) {
            throw new IllegalArgumentException("Invalid execution binding metadata");
        }
        requireExecutionId(runId);
        requireExecutionId(sessionId);
        IngressExecutionBinding requested = new IngressExecutionBinding(ingressHash, tupleHash, actorId, caller,
                runId, sessionId, policyDigest, operationsHash, executionHash,
                expiresAtEpochSecond, hardDeadlineEpochSecond);
        return withWriterLock(() -> {
            // Read time only after acquiring the mutex; waiting cannot prolong an ingress deadline.
            Instant now = Instant.now();
            if (expiresAtEpochSecond <= now.getEpochSecond() || hardDeadlineEpochSecond <= now.getEpochSecond()) {
                return BindingResult.CONFLICT;
            }
            // Preserve the repository-wide mutex -> account -> authorization state lock order.
            if (jdbc.queryForList("SELECT id FROM user_account WHERE id = ? FOR UPDATE",
                    Long.class, actorId).size() != 1
                    || jdbc.queryForList("SELECT user_id FROM auth_user_authorization_state WHERE user_id = ? FOR UPDATE",
                    Long.class, actorId).size() != 1) {
                return BindingResult.CONFLICT;
            }
            processUserGrantBoundaries(actorId, now);
            if (jdbc.queryForObject("""
                    SELECT COUNT(*) FROM user_account u
                    JOIN auth_user_authorization_state s ON s.user_id = u.id
                    WHERE u.id = ? AND u.account_status = 'ACTIVE' AND u.deleted = 0 AND s.status = 'ACTIVE'
                      AND s.permission_revision = ? AND s.credential_epoch = ?
                    """, Integer.class, actorId, expectedPermissionRevision, expectedCredentialEpoch) != 1) {
                return BindingResult.CONFLICT;
            }
            Optional<IngressExecutionBinding> existing = jdbc.query("""
                    SELECT ingress_request_hash, tuple_hash, actor_id, caller, run_id, session_id,
                           policy_digest, operations_hash, execution_hash, expires_at, hard_deadline
                    FROM auth_authorization_execution_binding WHERE ingress_request_hash = ?
                    """, (rs, row) -> new IngressExecutionBinding(rs.getString(1), rs.getString(2), rs.getLong(3),
                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                    rs.getString(9), rs.getLong(10), rs.getLong(11)), ingressHash).stream().findFirst();
            if (expiresAtEpochSecond <= Instant.now().getEpochSecond()) {
                return BindingResult.CONFLICT;
            }
            if (existing.isPresent()) {
                return existing.get().equals(requested) ? BindingResult.IDEMPOTENT : BindingResult.CONFLICT;
            }
            // All binding writers hold the database mutex, including writers in other Auth instances.
            if (jdbc.update("""
                    INSERT INTO auth_authorization_execution_binding
                    (ingress_request_hash, tuple_hash, actor_id, caller, run_id, session_id, policy_digest,
                     operations_hash, execution_hash, expires_at, hard_deadline) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, ingressHash, tupleHash, actorId, caller, runId, sessionId, policyDigest,
                    operationsHash, executionHash, expiresAtEpochSecond, hardDeadlineEpochSecond) != 1) {
                throw new IllegalStateException("Execution binding was not recorded");
            }
            return BindingResult.CREATED;
        });
    }

    private static void requireExecutionDigest(String digest) {
        if (digest == null || !digest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid execution binding digest");
        }
    }

    private static void requireExecutionId(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("Invalid execution binding identifier");
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException("Invalid execution binding identifier");
            }
            if (Character.isHighSurrogate(character)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) {
                    throw new IllegalArgumentException("Invalid execution binding identifier");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Invalid execution binding identifier");
            }
        }
    }

    public enum BindingResult { CREATED, IDEMPOTENT, CONFLICT }

    private record IngressExecutionBinding(String ingressHash, String tupleHash, long actorId, String caller,
                                          String runId, String sessionId, String policyDigest, String operationsHash,
                                          String executionHash, long expiresAtEpochSecond,
                                          long hardDeadlineEpochSecond) { }

    /** Call after validating the signature, binding and deadline. Hash only; never persist a raw nonce. */
    public boolean consumeNonce(String caller, String keyId, String nonceHash, Instant now, Instant expiresAt) {
        if (caller == null || !caller.matches("[A-Za-z0-9._-]{1,64}")
                || keyId == null || !keyId.matches("[A-Za-z0-9._-]{1,64}")
                || nonceHash == null || !nonceHash.matches("[a-f0-9]{64}")
                || !expiresAt.isAfter(now) || expiresAt.isAfter(now.plusSeconds(300))) {
            throw new IllegalArgumentException("Invalid nonce binding");
        }
        try {
            return jdbc.update("""
                    INSERT INTO auth_authorization_nonce (caller, key_id, nonce_hash, expires_at)
                    VALUES (?, ?, ?, ?)
                    """, caller, keyId, nonceHash, timestamp(expiresAt)) == 1;
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            return false;
        }
    }

    /** Initial small-deployment serialization; nested repository writes join this same short transaction. */
    public <T> T withWriterLock(Supplier<T> work) {
        return transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).execute(status -> {
            lockWriter();
            return work.get();
        });
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    public int processGrantBoundaries(Instant now, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid boundary batch");
        return withWriterLock(() -> {
            List<Long> users = jdbc.queryForList("""
                    SELECT DISTINCT due.user_id FROM (
                        SELECT user_id, role_id, 'START' boundary_kind, valid_from boundary_at
                        FROM auth_user_role WHERE active=1 AND valid_from IS NOT NULL AND valid_from <= ?
                        UNION ALL
                        SELECT user_id, role_id, 'END' boundary_kind, valid_until boundary_at
                        FROM auth_user_role WHERE active=1 AND valid_until IS NOT NULL AND valid_until <= ?
                    ) due JOIN user_account u ON u.id=due.user_id
                    WHERE NOT EXISTS (SELECT 1 FROM auth_authorization_grant_boundary b
                        WHERE b.user_id=due.user_id AND b.role_id=due.role_id
                          AND b.boundary_kind=due.boundary_kind AND b.boundary_at=due.boundary_at)
                    ORDER BY due.user_id LIMIT ?
                    """, Long.class, timestamp(now), timestamp(now), limit);
            int count = 0;
            for (Long user : users) count += processUserGrantBoundaries(user, now);
            return count;
        });
    }

    /** Every due boundary is claimed/versioned in the same transaction before assembling new content. */
    public int processUserGrantBoundaries(long userId, Instant now) {
        return withWriterLock(() -> {
            List<GrantBoundary> due = jdbc.query("""
                    SELECT due.role_id, due.boundary_kind, due.boundary_at FROM (
                        SELECT user_id, role_id, 'START' boundary_kind, valid_from boundary_at
                        FROM auth_user_role WHERE user_id=? AND active=1 AND valid_from IS NOT NULL AND valid_from <= ?
                        UNION ALL
                        SELECT user_id, role_id, 'END' boundary_kind, valid_until boundary_at
                        FROM auth_user_role WHERE user_id=? AND active=1 AND valid_until IS NOT NULL AND valid_until <= ?
                    ) due JOIN user_account u ON u.id=due.user_id
                    WHERE NOT EXISTS (SELECT 1 FROM auth_authorization_grant_boundary b
                        WHERE b.user_id=due.user_id AND b.role_id=due.role_id
                          AND b.boundary_kind=due.boundary_kind AND b.boundary_at=due.boundary_at)
                    ORDER BY due.boundary_at, due.role_id, due.boundary_kind
                    """, (rs, row) -> new GrantBoundary(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant()),
                    userId, timestamp(now), userId, timestamp(now));
            if (due.isEmpty()) return 0;
            mutateUsers(List.of(userId), "GRANT_BOUNDARY", false, template -> {
                for (GrantBoundary boundary : due) {
                    template.update("""
                            INSERT INTO auth_authorization_grant_boundary
                            (user_id, role_id, boundary_kind, boundary_at, processed_at) VALUES (?,?,?,?,?)
                            """, userId, boundary.roleId(), boundary.kind(), timestamp(boundary.at()), timestamp(now));
                }
            });
            return due.size();
        });
    }

    /** Called inside a replacing-grants transaction, whose revision already records all past boundaries. */
    void resetGrantBoundaries(long userId, Instant now) {
        jdbc.update("DELETE FROM auth_authorization_grant_boundary WHERE user_id=?", userId);
        jdbc.update("""
                INSERT INTO auth_authorization_grant_boundary
                (user_id,role_id,boundary_kind,boundary_at,processed_at)
                SELECT user_id,role_id,'START',valid_from,? FROM auth_user_role
                WHERE user_id=? AND active=1 AND valid_from IS NOT NULL AND valid_from <= ?
                """, timestamp(now), userId, timestamp(now));
        jdbc.update("""
                INSERT INTO auth_authorization_grant_boundary
                (user_id,role_id,boundary_kind,boundary_at,processed_at)
                SELECT user_id,role_id,'END',valid_until,? FROM auth_user_role
                WHERE user_id=? AND active=1 AND valid_until IS NOT NULL AND valid_until <= ?
                """, timestamp(now), userId, timestamp(now));
    }

    private record GrantBoundary(long roleId, String kind, Instant at) { }

    private void lockWriter() {
        if (jdbc.queryForList("SELECT lock_id FROM auth_authorization_writer_lock WHERE lock_id = 1 FOR UPDATE",
                Integer.class).size() != 1) {
            throw new IllegalStateException("Authorization migration/writer mutex missing");
        }
    }

    private UserVersion readVersion(long userId) {
        return currentVersion(userId).orElseThrow();
    }

    private TransactionTemplate transaction(int isolation) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(isolation);
        template.setTimeout(10);
        return template;
    }

    private static void requireReason(String reason) {
        if (reason == null || !reason.matches("[A-Z0-9_]{1,96}")) {
            throw new IllegalArgumentException("Use a registered machine-readable reason");
        }
    }

    private static String versionsJson(List<UserVersion> versions) {
        return "[" + String.join(",", versions.stream().map(version -> "{\"userId\":" + version.userId()
                + ",\"permissionRevision\":" + version.permissionRevision() + ",\"credentialEpoch\":"
                + version.credentialEpoch() + ",\"status\":\"" + version.status() + "\"}").toList()) + "]";
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record UserVersion(long userId, long permissionRevision, long credentialEpoch, String status) {
    }

    public record MutationResult(String eventId, List<UserVersion> versions) {
        public MutationResult {
            versions = List.copyOf(versions);
        }
    }

    public record OutboxClaim(String eventId, String affectedUsersJson, String revisionsJson, String reason,
                              int attempts, long leaseEpoch, Instant leaseUntil) {
    }
}
