package com.bkanent.auth.authorization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationPublicationFenceTest {
    JdbcTemplate jdbc;
    AuthorizationRepository repository;
    Instant now = Instant.parse("2026-10-23T10:00:00Z");

    @BeforeEach
    void setup() throws Exception {
        var fixture = new AuthorizationRepositoryTest(); fixture.setup();
        jdbc = fixture.jdbc; repository = fixture.repository;
    }

    @Test
    void exactUserMembershipAndFailedEventBlockRenewalUntilRepaired() {
        assertTrue(repository.renewalCurrent(1, 1, 1, "ACTIVE", now));
        String event = repository.mutateUsers(List.of(1L), "ACCOUNT_UPDATED", false, ignored -> { }).eventId();
        assertTrue(repository.pendingUserEvents(1));
        assertFalse(repository.pendingUserEvents(11));
        assertFalse(repository.renewalCurrent(1, 2, 1, "ACTIVE", now));
        jdbc.update("UPDATE auth_authorization_outbox SET next_attempt_at=?", Timestamp.from(now));
        var claim = repository.claimOutbox(1, now, Duration.ofSeconds(10)).get(0);
        assertTrue(repository.outboxLeaseValid(event, claim.leaseEpoch(), now));
        assertFalse(repository.outboxLeaseValid(event, claim.leaseEpoch()+1, now));
        assertTrue(repository.publicationCurrent(1, 2, 1, "ACTIVE", event, claim.leaseEpoch(), now));
        assertFalse(repository.publicationCurrent(1, 1, 1, "ACTIVE", event, claim.leaseEpoch(), now));
        assertFalse(repository.publicationCurrent(1, 2, 2, "ACTIVE", event, claim.leaseEpoch(), now));
        assertFalse(repository.publicationCurrent(1, 2, 1, "DISABLED", event, claim.leaseEpoch(), now));
        assertFalse(repository.failOutbox(event, claim.leaseEpoch()+1, now));
        assertTrue(repository.failOutbox(event, claim.leaseEpoch(), now));
        assertTrue(repository.pendingUserEvents(1));
        assertFalse(repository.renewalCurrent(1, 2, 1, "ACTIVE", now));
        assertFalse(repository.completeOutbox(event, claim.leaseEpoch(), now));
        assertFalse(repository.failOutbox(event, claim.leaseEpoch(), now));
    }

    @Test
    void publicationMustProcessDueGrantBeforeMatchingSignedSnapshotVersion() {
        jdbc.update("INSERT INTO auth_user_role(user_id,role_id,active,valid_until) VALUES (1,10,1,?)",
                Timestamp.from(now.plusSeconds(5)));
        String event = repository.mutateUsers(List.of(1L), "ACCOUNT_UPDATED", false, ignored -> { }).eventId();
        jdbc.update("UPDATE auth_authorization_outbox SET next_attempt_at=?", Timestamp.from(now));
        var claim = repository.claimOutbox(1, now, Duration.ofSeconds(10)).get(0);
        assertTrue(repository.publicationCurrent(1, 2, 1, "ACTIVE", event, claim.leaseEpoch(), now.plusSeconds(4)));
        assertFalse(repository.publicationCurrent(1, 2, 1, "ACTIVE", event, claim.leaseEpoch(), now.plusSeconds(5)));
        assertEquals(3, repository.currentVersion(1).orElseThrow().permissionRevision());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox WHERE reason='GRANT_BOUNDARY'", Integer.class));
    }

    @Test
    void lateClaimCannotPublishOrFailNewOwnersAndCompletionAllowsRenewal() {
        String event = repository.mutateUsers(List.of(1L), "ACCOUNT_UPDATED", false, ignored -> { }).eventId();
        jdbc.update("UPDATE auth_authorization_outbox SET next_attempt_at=?", Timestamp.from(now));
        var old = repository.claimOutbox(1, now, Duration.ofSeconds(10)).get(0);
        assertFalse(repository.outboxLeaseValid(event, old.leaseEpoch(), now.plusSeconds(10)));
        assertFalse(repository.failOutbox(event, old.leaseEpoch(), now.plusSeconds(10)));
        var live = repository.claimOutbox(1, now.plusSeconds(10), Duration.ofSeconds(10)).get(0);
        assertFalse(repository.publicationCurrent(1, 2, 1, "ACTIVE", event, old.leaseEpoch(), now.plusSeconds(11)));
        assertTrue(repository.publicationCurrent(1, 2, 1, "ACTIVE", event, live.leaseEpoch(), now.plusSeconds(11)));
        assertTrue(repository.completeOutbox(event, live.leaseEpoch(), now.plusSeconds(11)));
        assertTrue(repository.renewalCurrent(1, 2, 1, "ACTIVE", now.plusSeconds(11)));
        assertFalse(repository.publicationCurrent(1, 2, 1, "ACTIVE", event, live.leaseEpoch(), now.plusSeconds(11)));
    }
}
