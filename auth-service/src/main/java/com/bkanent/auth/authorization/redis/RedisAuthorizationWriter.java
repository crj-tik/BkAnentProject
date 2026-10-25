package com.bkanent.auth.authorization.redis;

import com.bkanent.auth.config.AuthAuthorizationProperties;
import com.bkanent.auth.config.AuthAuthorizationRedisProperties;
import com.bkanent.common.authz.*;
import com.bkanent.common.security.ServiceIdentityCrypto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Auth-only writer: inject a dedicated protected writer template, never a consumer/shared template. */
public final class RedisAuthorizationWriter {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final AuthAuthorizationRedisProperties settings;
    private final Clock clock;
    private final PrivateKey key;
    private final String keyId;
    private final Map<String, PublicKey> keys;
    private final DefaultRedisScript<Long> script;

    public RedisAuthorizationWriter(StringRedisTemplate protectedAuthWriter, ObjectMapper json,
                                    AuthAuthorizationProperties authority,
                                    AuthAuthorizationRedisProperties settings, Clock clock) {
        settings.validateEnabled();
        if (!authority.isEnabled()) throw new IllegalStateException("Authorization authority is disabled");
        this.redis = Objects.requireNonNull(protectedAuthWriter);
        this.json = Objects.requireNonNull(json);
        this.settings = settings;
        this.clock = Objects.requireNonNull(clock);
        this.keyId = authority.getResponseKeyId();
        this.key = authority.signingKey();
        this.keys = Map.of(keyId, ServiceIdentityCrypto.decodePublicKey(authority.getResponsePublicKey()));
        this.script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("authz/publish.lua"));
        script.setResultType(Long.class);
    }

    public AuthorizationSnapshot sign(AuthorizationSnapshot unsigned) {
        if (!keyId.equals(unsigned.keyId())) throw new IllegalArgumentException("Wrong authority key id");
        return AuthzSignature.sign(unsigned, key);
    }

    public AuthzFreshness attest(AuthorizationSnapshot snapshot, long epoch, AuthorizationStatus status,
                                Instant authorityReadStartedAt, String nonce) {
        long checkedAt = authorityReadStartedAt.getEpochSecond();
        return AuthzSignature.sign(new AuthzFreshness(snapshot.actorId(), snapshot.permissionRevision(), epoch,
                status, AuthzCanonicalHash.snapshotHash(snapshot), checkedAt,
                AuthzDeadlines.freshnessNotAfter(checkedAt), nonce, AuthzContract.ALGORITHM, keyId, null), key);
    }

    /** No caller can supply mutable TTL/issued time as signed snapshot facts. */
    public Outcome publish(AuthorizationSnapshot snapshot, AuthzFreshness head, boolean renewalOnly) {
        requireOutsideTransaction();
        verify(snapshot, head);
        Long result = redis.execute(script, List.of(headKey(head.userId()), snapshotKey(head.userId(),
                        head.permissionRevision()), fenceKey(head.userId())),
                Long.toString(head.permissionRevision()), Long.toString(head.credentialEpoch()),
                head.permissionHash(), head.status().name(), Long.toString(head.checkedAtEpochSecond()),
                Long.toString(head.notAfterEpochSecond()), encode(head),
                encode(new StoredSnapshot(snapshot, head.permissionHash())),
                Long.toString(settings.getSnapshotTtlSeconds()), renewalOnly ? "RENEW" : "PUBLISH");
        if (result == null) throw new IllegalStateException("Redis publication was not confirmed");
        return Outcome.from(result);
    }

    public void notify(VersionNotification notification) {
        requireOutsideTransaction();
        redis.convertAndSend(settings.getChannel(), encode(notification));
    }

    private void verify(AuthorizationSnapshot snapshot, AuthzFreshness head) {
        Instant now = clock.instant();
        AuthzSignature.verifySnapshot(snapshot, keys, now);
        // Common consumer verifier rejects DISABLED/DELETED. The writer must also sign invalidation heads.
        verifyFreshnessSignature(head);
        if (!AuthzContract.ALGORITHM.equals(head.algorithm())
                || !snapshot.actorId().equals(head.userId())
                || snapshot.permissionRevision() != head.permissionRevision()
                || !AuthzCanonicalHash.snapshotHash(snapshot).equals(head.permissionHash())
                || head.checkedAtEpochSecond() > now.getEpochSecond()
                || head.notAfterEpochSecond() <= now.getEpochSecond()) {
            throw new IllegalArgumentException("Invalid authorization publication binding or deadline");
        }
    }

    private void verifyFreshnessSignature(AuthzFreshness head) {
        if (!keyId.equals(head.keyId())) throw new IllegalArgumentException("Unknown authority key id");
        // Public common verifier deliberately rejects inactive accounts. Verify the exact common wire
        // signature by comparing its deterministic Ed25519 re-signature, including status/nonce/kid.
        AuthzFreshness signed = AuthzSignature.sign(new AuthzFreshness(head.userId(), head.permissionRevision(),
                head.credentialEpoch(), head.status(), head.permissionHash(), head.checkedAtEpochSecond(),
                head.notAfterEpochSecond(), head.nonce(), head.algorithm(), head.keyId(), null), key);
        if (!Objects.equals(signed.signature(), head.signature())) {
            throw new IllegalArgumentException("Invalid authority freshness signature");
        }
        // ACTIVE uses the shared verifier as well; inactive signatures were checked above exactly.
        if (head.status() == AuthorizationStatus.ACTIVE) AuthzSignature.verifyFreshness(head, keys, clock.instant());
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid authorization wire value"); }
    }

    public String headKey(String userId) { return base(userId) + "head"; }
    public String snapshotKey(String userId, long revision) {
        if (revision <= 0) throw new IllegalArgumentException("Invalid revision");
        return base(userId) + "snapshot:" + revision;
    }
    public String fenceKey(String userId) { return base(userId) + "fence"; }
    private String base(String userId) {
        if (userId == null || !userId.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid Auth user id");
        return settings.getNamespace() + ":{" + userId + "}:";
    }

    public static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Authorization network publication cannot run in a SQL transaction");
        }
    }

    public record StoredSnapshot(AuthorizationSnapshot snapshot, String contentHash) {
        @Override public String toString() { return "StoredSnapshot[REDACTED]"; }
    }
    public record VersionNotification(String eventId, long userId, long newRevision, long credentialEpoch,
                                      String reason) {
        public VersionNotification {
            if (eventId == null || !eventId.matches("[A-Za-z0-9._-]{1,64}") || userId <= 0
                    || newRevision <= 0 || credentialEpoch < 0 || reason == null
                    || !reason.matches("[A-Z0-9_]{1,96}")) throw new IllegalArgumentException("Invalid notification");
        }
    }
    public enum Outcome {
        PUBLISHED(1), IDEMPOTENT(2), STALE_VERSION(-1), CONTENT_CONFLICT(-2), STALE_CHECK(-3), EXPIRED(-4),
        RENEWAL_MISSING(-5), INVALID_FENCE(-6);
        private final long code;
        Outcome(long code) { this.code = code; }
        public boolean accepted() { return this == PUBLISHED || this == IDEMPOTENT; }
        private static Outcome from(long value) {
            for (Outcome outcome : values()) if (outcome.code == value) return outcome;
            throw new IllegalStateException("Unknown Redis CAS result");
        }
    }
}
