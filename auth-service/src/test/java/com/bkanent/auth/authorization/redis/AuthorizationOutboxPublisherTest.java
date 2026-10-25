package com.bkanent.auth.authorization.redis;

import com.bkanent.auth.authorization.AuthorizationRepository;
import com.bkanent.auth.authorization.EffectivePermissionService;
import com.bkanent.auth.config.AuthAuthorizationProperties;
import com.bkanent.auth.config.AuthAuthorizationRedisProperties;
import com.bkanent.common.authz.AuthorizationSnapshot;
import com.bkanent.common.authz.AuthorizationStatus;
import com.bkanent.common.authz.AuthzContract;
import com.bkanent.common.authz.AuthzSignature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthorizationOutboxPublisherTest {
    private final MutableClock clock = new MutableClock();
    private final AuthorizationRepository repository = mock(AuthorizationRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisActiveAuthorizationActors active = mock(RedisActiveAuthorizationActors.class);
    private final AuthAuthorizationRedisProperties settings = new AuthAuthorizationRedisProperties();
    private AuthorizationOutboxPublisher publisher;
    private RedisAuthorizationWriter writer;
    private PublicKey authorityPublicKey;
    private AuthorizationRepository.OutboxClaim claim;
    private EffectivePermissionService.Snapshot facts;

    @BeforeEach
    void setup() throws Exception {
        settings.setEnabled(true);
        settings.setProtectedWriterConfigured(true);
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        authorityPublicKey = keys.getPublic();
        AuthAuthorizationProperties authority = mock(AuthAuthorizationProperties.class);
        when(authority.isEnabled()).thenReturn(true);
        when(authority.getResponseKeyId()).thenReturn("auth-service-current");
        when(authority.signingKey()).thenReturn(keys.getPrivate());
        when(authority.getResponsePublicKey()).thenReturn(Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        writer = new RedisAuthorizationWriter(redis, new ObjectMapper(), authority, settings, clock);
        publisher = new AuthorizationOutboxPublisher(repository, writer, active, settings, authority, new ObjectMapper(), clock);
        claim = claim(1);
        facts = new EffectivePermissionService.Snapshot(7, 11, 3, "ACTIVE", false, 1, List.of("memory.read"), null);
        when(repository.snapshot(eq(7L), any())).thenReturn(Optional.of(facts));
        when(repository.outboxLeaseValid(eq("event-1"), eq(4L), any())).thenReturn(true);
        when(repository.publicationCurrent(eq(7L), eq(11L), eq(3L), eq("ACTIVE"), eq("event-1"), eq(4L), any())).thenReturn(true);
        when(repository.renewalCurrent(eq(7L), eq(11L), eq(3L), eq("ACTIVE"), any())).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
    }

    @Test
    void latestSnapshotIsReadBeforeRedisAndNotifyBeforeLeaseCompletion() {
        publisher.dispatch(claim);
        var order = inOrder(repository, redis);
        order.verify(repository).outboxLeaseValid(eq("event-1"), eq(4L), any());
        order.verify(repository).snapshot(7L, clock.instant());
        order.verify(repository).outboxLeaseValid(eq("event-1"), eq(4L), any());
        order.verify(repository).publicationCurrent(eq(7L), eq(11L), eq(3L), eq("ACTIVE"), eq("event-1"), eq(4L), any());
        order.verify(redis).execute(any(RedisScript.class), eq(List.of("authz:{7}:head", "authz:{7}:snapshot:11", "authz:{7}:fence")), any(Object[].class));
        order.verify(repository).outboxLeaseValid(eq("event-1"), eq(4L), any());
        order.verify(redis).convertAndSend(eq("authz:versions"), contains("\"newRevision\":11"));
        order.verify(repository).outboxLeaseValid(eq("event-1"), eq(4L), any());
        order.verify(repository).completeOutbox(eq("event-1"), eq(4L), any());
        verify(repository, never()).retryOutbox(anyString(), anyLong(), any(), any());
    }

    @Test
    void realWriterAttestationUsesCanonical32ByteNonceAndReadStartDeadlineWithoutRedis() {
        byte[] nonceBytes = new byte[32];
        new SecureRandom().nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        AuthorizationSnapshot snapshot = writer.sign(new AuthorizationSnapshot("7", 11, 1, false,
                List.of("memory.read"), null, AuthzContract.ALGORITHM, "auth-service-current", null));
        Instant readStartedAt = clock.instant().minusSeconds(5);
        var head = writer.attest(snapshot, 3, AuthorizationStatus.ACTIVE, readStartedAt, nonce);
        assertEquals(43, head.nonce().length());
        assertArrayEquals(nonceBytes, Base64.getUrlDecoder().decode(head.nonce()));
        assertEquals(readStartedAt.getEpochSecond(), head.checkedAtEpochSecond());
        assertEquals(readStartedAt.plusSeconds(30).getEpochSecond(), head.notAfterEpochSecond());
        assertDoesNotThrow(() -> AuthzSignature.verifyFreshnessAttestation(head,
                Map.of("auth-service-current", authorityPublicKey), clock.instant()));
        verifyNoInteractions(redis);
    }

    @Test
    void actualPublisherNonceIsCanonicalAndSignedBeforeRedisPublication() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(call -> {
            var head = new ObjectMapper().readValue((String) call.getArgument(8),
                    com.bkanent.common.authz.AuthzFreshness.class);
            assertEquals(43, head.nonce().length());
            assertEquals(32, Base64.getUrlDecoder().decode(head.nonce()).length);
            assertEquals(head.nonce(), Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(Base64.getUrlDecoder().decode(head.nonce())));
            AuthzSignature.verifyFreshnessAttestation(head, Map.of("auth-service-current", authorityPublicKey), clock.instant());
            return 1L;
        });
        publisher.dispatch(claim);
        verify(repository).completeOutbox(eq("event-1"), eq(4L), any());
        verify(repository, never()).retryOutbox(anyString(), anyLong(), any(), any());
    }

    @Test
    void emptyAffectedActorAuditEventCompletesWithoutUserPublication() {
        publisher.dispatch(new AuthorizationRepository.OutboxClaim("event-1", "[]", "[]", "ROLE_CREATED", 1, 4,
                clock.instant().plusSeconds(30)));
        verify(repository).completeOutbox(eq("event-1"), eq(4L), any());
        verify(repository, never()).snapshot(anyLong(), any());
        verify(repository, never()).retryOutbox(anyString(), anyLong(), any(), any());
        verify(repository, never()).failOutbox(anyString(), anyLong(), any());
        verifyNoInteractions(redis);
    }

    @Test
    void emptyAuditEventWithReclaimedLeaseCannotComplete() {
        when(repository.outboxLeaseValid(eq("event-1"), eq(4L), any())).thenReturn(true, false);
        publisher.dispatch(new AuthorizationRepository.OutboxClaim("event-1", "[]", "[]", "ROLE_CREATED", 1, 4,
                clock.instant().plusSeconds(30)));
        verify(repository, never()).completeOutbox(anyString(), anyLong(), any());
        verifyNoInteractions(redis);
    }

    @Test
    void unknownRedisOutcomeRetriesSameEventThenIdempotentCasCompletes() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("timeout after apply")).thenReturn(2L);
        publisher.dispatch(claim);
        verify(repository).retryOutbox(eq("event-1"), eq(4L), any(), any());
        verify(redis, never()).convertAndSend(anyString(), any());
        verify(repository, never()).completeOutbox(anyString(), anyLong(), any());
        publisher.dispatch(claim);
        verify(redis).convertAndSend(eq("authz:versions"), contains("event-1"));
        verify(repository).completeOutbox(eq("event-1"), eq(4L), any());
    }

    @Test
    void failedLastAttemptBecomesFailedRatherThanInfiniteRetry() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(new IllegalStateException());
        publisher.dispatch(claim(settings.getMaxAttempts()));
        verify(repository).failOutbox(eq("event-1"), eq(4L), any());
        verify(repository, never()).retryOutbox(anyString(), anyLong(), any(), any());
    }

    @Test
    void crashedClaimAlreadyBeyondMaxAttemptsDoesNotCallRedis() {
        publisher.dispatch(claim(settings.getMaxAttempts() + 1));
        verify(repository).failOutbox(eq("event-1"), eq(4L), any());
        verify(repository, never()).snapshot(anyLong(), any());
        verifyNoInteractions(redis);
    }

    @Test
    void expiredLeaseCannotPublishOrConfirm() {
        clock.advance(31);
        publisher.dispatch(claim);
        verifyNoInteractions(repository, redis);
    }

    @Test
    void reclaimedEpochIsRejectedBeforeLoadingContent() {
        when(repository.outboxLeaseValid(eq("event-1"), eq(4L), any())).thenReturn(false);
        publisher.dispatch(claim);
        verify(repository, never()).snapshot(anyLong(), any());
        verify(repository, never()).completeOutbox(anyString(), anyLong(), any());
        verifyNoInteractions(redis);
    }

    @Test
    void leaseExpiresDuringRedisPublicationSoLateWorkerCannotNotifyOrComplete() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(call -> {
            clock.advance(31);
            return 1L;
        });
        publisher.dispatch(claim);
        verify(redis, never()).convertAndSend(anyString(), any());
        verify(repository, never()).completeOutbox(anyString(), anyLong(), any());
        verify(repository, never()).retryOutbox(anyString(), anyLong(), any(), any());
    }

    @Test
    void leaseExpiresInNotificationSoLateWorkerCannotComplete() {
        doAnswer(call -> { clock.advance(31); return 0L; }).when(redis).convertAndSend(anyString(), any());
        publisher.dispatch(claim);
        verify(repository, never()).completeOutbox(anyString(), anyLong(), any());
    }

    @Test
    void newerMysqlVersionAfterLoadRejectsOldHead() {
        when(repository.publicationCurrent(eq(7L), eq(11L), eq(3L), eq("ACTIVE"), eq("event-1"), eq(4L), any())).thenReturn(false);
        publisher.dispatch(claim);
        verifyNoInteractions(redis);
        verify(repository).retryOutbox(eq("event-1"), eq(4L), any(), any());
    }

    @Test
    void lateReadDoesNotResetFreshnessDeadlineFromCompletionTime() {
        // Give the event a longer lease to isolate the independent 30-second freshness ceiling.
        claim = new AuthorizationRepository.OutboxClaim("event-1", "[7]", "[{}]", "ROLE_CHANGED", 1, 4,
                clock.instant().plusSeconds(60));
        when(repository.snapshot(eq(7L), any())).thenAnswer(call -> { clock.advance(31); return Optional.of(facts); });
        publisher.dispatch(claim);
        verifyNoInteractions(redis);
        verify(repository).retryOutbox(eq("event-1"), eq(4L), any(), any());
    }

    @Test
    void pendingEventNeverRenewsOldHead() {
        when(active.page(0)).thenReturn(List.of(new RedisActiveAuthorizationActors.Actor(7, clock.instant().plusSeconds(200))));
        when(repository.pendingUserEvents(7)).thenReturn(true);
        publisher.renewActive();
        verify(repository, never()).snapshot(anyLong(), any());
        verifyNoInteractions(redis);
    }

    @Test
    void renewalRejectsMutationCommittedDuringSnapshotRead() {
        when(active.page(0)).thenReturn(List.of(new RedisActiveAuthorizationActors.Actor(7, clock.instant().plusSeconds(200))));
        when(repository.renewalCurrent(eq(7L), eq(11L), eq(3L), eq("ACTIVE"), any())).thenReturn(false);
        publisher.renewActive();
        verify(repository).snapshot(eq(7L), any());
        verifyNoInteractions(redis);
    }

    @Test
    void restartRecoversActiveActorAndRebuildsExpiredHeadFromFreshMysql() {
        when(active.page(0)).thenReturn(List.of(new RedisActiveAuthorizationActors.Actor(7, clock.instant().plusSeconds(200))));
        publisher.renewActive();
        verify(repository).snapshot(eq(7L), any());
        verify(repository).renewalCurrent(eq(7L), eq(11L), eq(3L), eq("ACTIVE"), any());
        verify(redis).execute(any(RedisScript.class), eq(List.of("authz:{7}:head", "authz:{7}:snapshot:11", "authz:{7}:fence")), any(Object[].class));
        verify(redis, never()).convertAndSend(anyString(), any());
    }

    @Test
    void activeRegistrationCapsRunLifetimeTo300Seconds() {
        when(active.register(eq(7L), any())).thenReturn(true);
        publisher.registerActive(7, clock.instant().plusSeconds(900));
        verify(active).register(7, clock.instant().plusSeconds(300));
    }

    @Test
    void publisherDoesNotHoldActiveMapMonitorAcrossSqlOrRedisNetworkCalls() throws Exception {
        var activeField = AuthorizationOutboxPublisher.class.getDeclaredField("active");
        activeField.setAccessible(true);
        Object monitor = activeField.get(publisher);
        for (var method : AuthorizationOutboxPublisher.class.getDeclaredMethods()) {
            if (List.of("registerActive", "dispatch", "renewActive").contains(method.getName())) {
                assertFalse(java.lang.reflect.Modifier.isSynchronized(method.getModifiers()));
            }
        }
        when(active.register(eq(7L), any())).thenAnswer(call -> {
            assertFalse(Thread.holdsLock(monitor));
            return true;
        });
        when(repository.snapshot(eq(7L), any())).thenAnswer(call -> {
            assertFalse(Thread.holdsLock(monitor));
            return Optional.of(facts);
        });
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(call -> {
            assertFalse(Thread.holdsLock(monitor));
            return 1L;
        });
        when(active.page(0)).thenReturn(List.of());
        publisher.registerActive(7, clock.instant().plusSeconds(200));
        publisher.dispatch(claim);
        publisher.renewActive();
        verify(repository).completeOutbox(eq("event-1"), eq(4L), any());
    }

    @Test
    void networkAndDispatcherRejectAmbientSqlTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> publisher.dispatch(claim));
            assertThrows(IllegalStateException.class, publisher::renewActive);
            assertThrows(IllegalStateException.class, () -> writer.notify(
                    new RedisAuthorizationWriter.VersionNotification("event-1", 7, 11, 3, "ROLE_CHANGED")));
            assertThrows(IllegalStateException.class, () -> writer.publish(null, null, false));
            verifyNoInteractions(repository, redis);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    private AuthorizationRepository.OutboxClaim claim(int attempts) {
        return new AuthorizationRepository.OutboxClaim("event-1", "[7]", "[{\"permissionRevision\":2}]",
                "ROLE_CHANGED", attempts, 4, clock.instant().plusSeconds(30));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-23T10:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
