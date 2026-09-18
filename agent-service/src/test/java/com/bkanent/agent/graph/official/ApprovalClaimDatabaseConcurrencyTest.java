package com.bkanent.agent.graph.official;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes the database-level approval claim race against a real MySQL instance.
 *
 * <p>The test is opt-in so the normal unit-test suite remains self-contained. Set
 * {@code BK_AGENT_DB_URL}, {@code BK_AGENT_DB_USER} and {@code BK_AGENT_DB_PASSWORD}
 * to run it against the local development database.</p>
 */
class ApprovalClaimDatabaseConcurrencyTest {

    @Test
    void uniqueApprovalIdAllowsOnlyOneConcurrentClaim() throws Exception {
        String url = System.getenv("BK_AGENT_DB_URL");
        String user = System.getenv("BK_AGENT_DB_USER");
        String password = System.getenv("BK_AGENT_DB_PASSWORD");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "BK_AGENT_DB_URL is not configured; skipping real database concurrency test");
        Assumptions.assumeTrue(user != null && !user.isBlank(),
                "BK_AGENT_DB_USER is not configured; skipping real database concurrency test");
        Assumptions.assumeTrue(password != null,
                "BK_AGENT_DB_PASSWORD is not configured; skipping real database concurrency test");

        String approvalId = "db-race-" + System.nanoTime();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> claims = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                claims.add(executor.submit(() -> {
                    try (Connection connection = DriverManager.getConnection(url, user, password)) {
                        connection.setAutoCommit(true);
                        ready.countDown();
                        start.await();
                        try (PreparedStatement statement = connection.prepareStatement(
                                "INSERT INTO agent_workflow_approval_claim "
                                        + "(approval_id, task_id, session_id, approval_version, decision_status) "
                                        + "VALUES (?, ?, ?, ?, ?)")) {
                            statement.setString(1, approvalId);
                            statement.setString(2, "task-db-race");
                            statement.setString(3, "session-db-race");
                            statement.setInt(4, 1);
                            statement.setString(5, "PROCESSING");
                            statement.executeUpdate();
                            return true;
                        } catch (SQLException exception) {
                            if ("23000".equals(exception.getSQLState())) {
                                return false;
                            }
                            throw exception;
                        }
                    }
                }));
            }
            ready.await();
            start.countDown();
            assertThat(claims.get(0).get() ^ claims.get(1).get()).isTrue();
        } finally {
            executor.shutdownNow();
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM agent_workflow_approval_claim WHERE approval_id = ?")) {
                statement.setString(1, approvalId);
                statement.executeUpdate();
            }
        }
    }
}
