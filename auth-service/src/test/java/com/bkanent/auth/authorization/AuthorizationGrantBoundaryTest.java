package com.bkanent.auth.authorization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationGrantBoundaryTest {
    private AuthorizationRepositoryTest fixture;
    private final Instant now=Instant.parse("2026-10-23T10:00:00Z");

    @BeforeEach
    void setup() throws Exception { fixture=new AuthorizationRepositoryTest(); fixture.setup(); }

    @Test
    void futureStartAndExpiryEachIncrementOnceBeforeChangingSnapshotContent() {
        grant(1,10,now.plusSeconds(10),now.plusSeconds(20));
        var before=fixture.repository.snapshot(1,now).orElseThrow();
        assertTrue(before.permissions().isEmpty()); assertEquals(1,before.permissionRevision());
        var started=fixture.repository.snapshot(1,now.plusSeconds(10)).orElseThrow();
        assertEquals(2,started.permissionRevision()); assertEquals(java.util.List.of("listing.read.all"),started.permissions());
        assertEquals(started,fixture.repository.snapshot(1,now.plusSeconds(11)).orElseThrow());
        var expired=fixture.repository.snapshot(1,now.plusSeconds(20)).orElseThrow();
        assertEquals(3,expired.permissionRevision()); assertTrue(expired.permissions().isEmpty());
        assertEquals(expired,fixture.repository.snapshot(1,now.plusSeconds(21)).orElseThrow());
        assertEquals(2,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_grant_boundary",Integer.class));
        assertEquals(2,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox",Integer.class));
    }

    @Test
    void schedulerWithoutUserRequestsPersistsDueChangeAndIsRepeatSafe() {
        grant(1,10,null,now.plusSeconds(10));
        assertEquals(0,fixture.repository.processGrantBoundaries(now,100));
        assertEquals(1,fixture.repository.processGrantBoundaries(now.plusSeconds(10),100));
        assertEquals(2,fixture.repository.currentVersion(1).orElseThrow().permissionRevision());
        assertEquals(0,fixture.repository.processGrantBoundaries(now.plusSeconds(11),100));
        assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox",Integer.class));
    }

    @Test
    void inactiveGrantSkippedAndMultiplePastBoundariesCoalesceOneVersion() {
        grant(1,10,now.minusSeconds(20),now.minusSeconds(10));
        grant(2,20,null,now.minusSeconds(10));
        fixture.jdbc.update("UPDATE auth_user_role SET active=0 WHERE user_id=2");
        assertEquals(2,fixture.repository.processGrantBoundaries(now,100));
        assertEquals(2,fixture.repository.currentVersion(1).orElseThrow().permissionRevision());
        assertEquals(1,fixture.repository.currentVersion(2).orElseThrow().permissionRevision());
    }

    @Test
    void concurrentBoundaryWorkersOnlyProduceOneRevisionAndEvent() throws Exception {
        grant(1,10,null,now);
        var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            var one=pool.submit(() -> { start.await(); return fixture.repository.processGrantBoundaries(now,100); });
            var two=pool.submit(() -> { start.await(); return fixture.repository.processGrantBoundaries(now,100); });
            start.countDown();
            assertEquals(1,one.get(10,TimeUnit.SECONDS)+two.get(10,TimeUnit.SECONDS));
            assertEquals(2,fixture.repository.currentVersion(1).orElseThrow().permissionRevision());
            assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_outbox",Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void outboxFailureRollsBackMarkerAndVersionForRetry() {
        grant(1,10,null,now);
        fixture.jdbc.execute("ALTER TABLE auth_authorization_outbox ADD CONSTRAINT reject_reason CHECK(reason <> 'GRANT_BOUNDARY')");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> fixture.repository.processGrantBoundaries(now,100));
        assertEquals(1,fixture.repository.currentVersion(1).orElseThrow().permissionRevision());
        assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM auth_authorization_grant_boundary",Integer.class));
        fixture.jdbc.execute("ALTER TABLE auth_authorization_outbox DROP CONSTRAINT reject_reason");
        assertEquals(1,fixture.repository.processGrantBoundaries(now,100));
    }

    private void grant(long user,long role,Instant from,Instant until) {
        fixture.jdbc.update("INSERT INTO auth_user_role(user_id,role_id,valid_from,valid_until) VALUES (?,?,?,?)",
                user,role,from==null?null:Timestamp.from(from),until==null?null:Timestamp.from(until));
    }
}
