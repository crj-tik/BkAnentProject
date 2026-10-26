package com.bkanent.auth.authorization.redis;

import com.bkanent.auth.authorization.AuthorizationRepository;
import com.bkanent.auth.authorization.EffectivePermissionService;
import com.bkanent.auth.config.AuthAuthorizationProperties;
import com.bkanent.auth.config.AuthAuthorizationRedisProperties;
import com.bkanent.common.authz.AuthorizationSnapshot;
import com.bkanent.common.authz.AuthorizationStatus;
import com.bkanent.common.authz.AuthzContract;
import com.bkanent.common.authz.AuthzFreshness;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Short SQL claims/checks surround (never contain) signed Redis publication and notification. */
public final class AuthorizationOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(AuthorizationOutboxPublisher.class);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private final SecureRandom random = new SecureRandom();
    private final AuthorizationRepository repository;
    private final RedisAuthorizationWriter writer;
    private final RedisActiveAuthorizationActors sharedActive;
    private final AuthAuthorizationRedisProperties settings;
    private final AuthAuthorizationProperties authority;
    private final ObjectMapper json;
    private final Clock clock;
    private final Map<Long, Instant> active = new LinkedHashMap<>();

    public AuthorizationOutboxPublisher(AuthorizationRepository repository, RedisAuthorizationWriter writer,
            RedisActiveAuthorizationActors sharedActive, AuthAuthorizationRedisProperties settings,
            AuthAuthorizationProperties authority, ObjectMapper json, Clock clock) {
        settings.validateEnabled();
        this.repository = repository;
        this.writer = writer;
        this.sharedActive = sharedActive;
        this.settings = settings;
        this.authority = authority;
        this.json = json;
        this.clock = clock;
    }

    /** Called only after authenticated entry/refresh, never from an HTTP userId or restored checkpoint. */
    public void registerActive(long userId, Instant runHardDeadline) {
        RedisAuthorizationWriter.requireOutsideTransaction();
        Instant now = clock.instant();
        if (userId <= 0 || runHardDeadline == null || !runHardDeadline.isAfter(now)) {
            throw new IllegalArgumentException("Invalid active authorization lifetime");
        }
        Instant deadline = runHardDeadline.isAfter(now.plusSeconds(300)) ? now.plusSeconds(300) : runHardDeadline;
        if (!sharedActive.register(userId, deadline)) throw new IllegalStateException("Active authorization capacity exhausted");
        track(userId, deadline);
    }

    @Scheduled(fixedDelay = 1000)
    public void dispatch() {
        RedisAuthorizationWriter.requireOutsideTransaction();
        List<AuthorizationRepository.OutboxClaim> claims;
        try { claims = repository.claimOutbox(settings.getOutboxBatch(), clock.instant(), LEASE); }
        catch (RuntimeException unavailable) {
            log.warn("Authorization outbox claim unavailable");
            return;
        }
        for (AuthorizationRepository.OutboxClaim claim : claims) dispatch(claim);
    }

    public void dispatch(AuthorizationRepository.OutboxClaim claim) {
        RedisAuthorizationWriter.requireOutsideTransaction();
        try {
            if (!leaseValid(claim)) return;
            if (claim.attempts() > settings.getMaxAttempts()) {
                repository.failOutbox(claim.eventId(), claim.leaseEpoch(), clock.instant());
                return;
            }
            // Validate notification metadata before the first publication effect.
            new RedisAuthorizationWriter.VersionNotification(claim.eventId(), 1, 1, 0, claim.reason());
            for (long userId : users(claim)) {
                // The outbox's historical revisions are NOT publication content. Always rebuild latest truth.
                Publication publication = load(userId);
                var facts = publication.facts();
                if (!publicationCurrent(claim, facts) || !fresh(publication)) throw new IllegalStateException("Stale authority read");
                RedisAuthorizationWriter.Outcome outcome = writer.publish(publication.snapshot(), publication.head(), false);
                if (!outcome.accepted()) throw new IllegalStateException("Authorization CAS rejected");
                if (!leaseValid(claim)) return;
                writer.notify(new RedisAuthorizationWriter.VersionNotification(claim.eventId(), userId,
                        facts.permissionRevision(), facts.credentialEpoch(), claim.reason()));
            }
            if (leaseValid(claim)) repository.completeOutbox(claim.eventId(), claim.leaseEpoch(), clock.instant());
        } catch (RuntimeException failure) {
            // Unknown Redis outcome must be retried with the same event id; CAS prevents rollback/duplicate content.
            if (!leaseValid(claim)) return;
            if (claim.attempts() >= settings.getMaxAttempts()) {
                repository.failOutbox(claim.eventId(), claim.leaseEpoch(), clock.instant());
                log.warn("Authorization outbox exhausted event={} attempt={}", claim.eventId(), claim.attempts());
            } else {
                Instant now = clock.instant();
                repository.retryOutbox(claim.eventId(), claim.leaseEpoch(), now,
                        now.plusSeconds(Math.min(10, 1L << Math.min(claim.attempts(), 3))));
                log.warn("Authorization outbox retry scheduled event={} attempt={}", claim.eventId(), claim.attempts());
            }
        }
    }

    /** Every tick is bounded by maxActiveActors; each Redis page is bounded by renewalBatch. */
    @Scheduled(fixedDelay = 5000)
    public void renewActive() {
        RedisAuthorizationWriter.requireOutsideTransaction();
        pruneActive();
        try {
            for (int offset = 0; offset < settings.getMaxActiveActors(); offset += settings.getRenewalBatch()) {
                List<RedisActiveAuthorizationActors.Actor> page = sharedActive.page(offset);
                for (var actor : page) track(actor.userId(), actor.expiresAt());
                if (page.size() < settings.getRenewalBatch()) break;
            }
        } catch (RuntimeException unavailable) {
            // Local actors still require a fresh MySQL read; unavailable index never creates new authorization.
            log.warn("Authorization active index recovery unavailable");
        }
        for (var actor : activeActors().entrySet()) {
            long userId = actor.getKey();
            if (!actor.getValue().isAfter(clock.instant())) continue;
            try {
                if (repository.pendingUserEvents(userId)) continue;
                Publication publication = load(userId);
                var facts = publication.facts();
                if (!repository.renewalCurrent(userId, facts.permissionRevision(), facts.credentialEpoch(),
                        facts.status(), clock.instant()) || !fresh(publication)) continue;
                // A restart/expired Redis head may rebuild from this newly verified MySQL snapshot.
                // PUBLISH still uses the durable fence's same-revision/hash/checkedAt CAS, never old cached content.
                writer.publish(publication.snapshot(), publication.head(), false);
            } catch (RuntimeException unavailable) {
                log.warn("Authorization active head could not be renewed actor={}", userId);
            }
        }
    }

    private void track(long userId, Instant deadline) {
        Instant now = clock.instant();
        synchronized (active) {
            active.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
            if (active.containsKey(userId) || active.size() < settings.getMaxActiveActors()) {
                active.merge(userId, deadline, (old, next) -> old.isAfter(next) ? old : next);
            }
        }
    }

    private void pruneActive() {
        Instant now = clock.instant();
        synchronized (active) {
            active.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        }
    }

    private Map<Long, Instant> activeActors() {
        synchronized (active) {
            return new LinkedHashMap<>(active);
        }
    }

    private Publication load(long userId) {
        RedisAuthorizationWriter.requireOutsideTransaction();
        Instant readStartedAt = clock.instant();
        EffectivePermissionService.Snapshot facts = repository.snapshot(userId, readStartedAt).orElseThrow();
        RedisAuthorizationWriter.requireOutsideTransaction();
        AuthorizationSnapshot snapshot = writer.sign(new AuthorizationSnapshot(Long.toString(userId),
                facts.permissionRevision(), facts.catalogVersion(), facts.businessAdmin(), facts.permissions(),
                facts.grantDeadline() == null ? null : facts.grantDeadline().getEpochSecond(), facts.businessIdentities(),
                AuthzContract.ALGORITHM, authority.getResponseKeyId(), null));
        AuthzFreshness head = writer.attest(snapshot, facts.credentialEpoch(), AuthorizationStatus.valueOf(facts.status()),
                readStartedAt, freshnessNonce());
        return new Publication(facts, snapshot, head);
    }

    private String freshnessNonce() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean publicationCurrent(AuthorizationRepository.OutboxClaim claim, EffectivePermissionService.Snapshot facts) {
        return leaseValid(claim) && repository.publicationCurrent(facts.userId(), facts.permissionRevision(),
                facts.credentialEpoch(), facts.status(), claim.eventId(), claim.leaseEpoch(), clock.instant());
    }

    private boolean fresh(Publication publication) {
        return clock.instant().getEpochSecond() < publication.head().notAfterEpochSecond();
    }

    private boolean leaseValid(AuthorizationRepository.OutboxClaim claim) {
        return clock.instant().isBefore(claim.leaseUntil())
                && repository.outboxLeaseValid(claim.eventId(), claim.leaseEpoch(), clock.instant());
    }

    private List<Long> users(AuthorizationRepository.OutboxClaim claim) {
        try {
            if (claim.affectedUsersJson() == null || claim.revisionsJson() == null
                    || claim.affectedUsersJson().getBytes(StandardCharsets.UTF_8).length
                    + claim.revisionsJson().getBytes(StandardCharsets.UTF_8).length > settings.getMaxEventBytes()) {
                throw new IllegalArgumentException("Oversized authorization event");
            }
            JsonNode users = json.readTree(claim.affectedUsersJson());
            JsonNode revisions = json.readTree(claim.revisionsJson());
            if (!users.isArray() || users.size() > settings.getMaxUsersPerEvent()
                    || !revisions.isArray() || revisions.size() != users.size()) {
                throw new IllegalArgumentException("Invalid authorization event");
            }
            TreeSet<Long> ids = new TreeSet<>();
            for (JsonNode user : users) {
                if (!user.isIntegralNumber() || !user.canConvertToLong() || user.longValue() <= 0 || !ids.add(user.longValue())) {
                    throw new IllegalArgumentException("Invalid affected actor");
                }
            }
            return List.copyOf(ids);
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid authorization event"); }
    }

    private record Publication(EffectivePermissionService.Snapshot facts, AuthorizationSnapshot snapshot, AuthzFreshness head) { }
}
