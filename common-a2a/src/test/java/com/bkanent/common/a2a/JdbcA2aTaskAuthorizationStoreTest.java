package com.bkanent.common.a2a;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcA2aTaskAuthorizationStoreTest {
    @Test
    void nonceAndOriginalOwnerSurviveNewStoreInstance() {
        JdbcTemplate jdbc = database();
        var firstInstance = new JdbcA2aTaskAuthorizationStore(jdbc);
        var restartedInstance = new JdbcA2aTaskAuthorizationStore(jdbc);

        assertThat(firstInstance.consumeNonce(nonce(1), 200, 100)).isTrue();
        assertThat(restartedInstance.consumeNonce(nonce(1), 200, 100)).isFalse();

        assertThat(firstInstance.bindOrVerifyTaskOwner("task-1", "7", "agent-service",
                "ctx-1", "run-1", "session-1", true)).isTrue();
        assertThat(restartedInstance.bindOrVerifyTaskOwner("task-1", "7", "agent-service",
                "ctx-1", "run-1", "session-1", false)).isTrue();
        assertThat(restartedInstance.bindOrVerifyTaskOwner("task-1", "8", "agent-service",
                "ctx-1", "run-1", "session-1", false)).isFalse();
        assertThat(restartedInstance.bindOrVerifyTaskOwner("task-1", "7", "agent-service",
                "ctx-1", "run-2", "session-2", false)).isFalse();
        assertThat(restartedInstance.bindOrVerifyTaskOwner("unknown-task", "7", "agent-service",
                null, "run-1", "session-1", false)).isFalse();
    }

    @Test
    void onlyOneInstanceCanReserveTaskAndCleanupIsConditional() throws Exception {
        JdbcTemplate jdbc = database();
        var instanceA = new JdbcA2aTaskAuthorizationStore(jdbc);
        var instanceB = new JdbcA2aTaskAuthorizationStore(jdbc);
        instanceA.bindOrVerifyTaskOwner("task-2", "7", "agent-service", "ctx-1", "run-1", "session-1", true);

        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            results.add(pool.submit(() -> { start.await(); return instanceA.reserveTask("task-2", "7", nonce(2), 200, 100); }));
            results.add(pool.submit(() -> { start.await(); return instanceB.reserveTask("task-2", "7", nonce(3), 200, 100); }));
            start.countDown();
            long granted = 0;
            for (var result : results) if (result.get()) granted++;
            assertThat(granted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(instanceB.reserveTask("task-2", "7", nonce(4), 300, 200)).isTrue();
        instanceA.releaseTaskReservation("task-2", nonce(2));
        assertThat(jdbc.queryForObject("SELECT request_nonce FROM a2a_task_execution_reservation WHERE task_id = ?",
                String.class, "task-2")).isEqualTo(nonce(4));
        instanceB.releaseTaskReservation("task-2", nonce(4));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM a2a_task_execution_reservation", Integer.class)).isZero();
    }

    private static JdbcTemplate database() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:a2a-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE a2a_request_nonce (nonce CHAR(43) PRIMARY KEY, expires_at BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE a2a_task_owner (task_id VARCHAR(128) PRIMARY KEY, actor_id BIGINT NOT NULL, "
                + "caller_service VARCHAR(96) NOT NULL, context_id VARCHAR(128), run_id VARCHAR(128) NOT NULL, "
                + "session_id VARCHAR(128) NOT NULL)");
        jdbc.execute("CREATE TABLE a2a_task_execution_reservation (task_id VARCHAR(128) PRIMARY KEY, "
                + "actor_id BIGINT NOT NULL, request_nonce CHAR(43) NOT NULL UNIQUE, reserved_until BIGINT NOT NULL)");
        return jdbc;
    }

    private static String nonce(int seed) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) seed);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
