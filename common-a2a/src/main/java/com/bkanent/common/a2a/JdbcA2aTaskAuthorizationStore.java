package com.bkanent.common.a2a;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;

/** MySQL-backed shared state; schema is provisioned by sql/migrations. */
public final class JdbcA2aTaskAuthorizationStore implements A2aTaskAuthorizationStore {
    private final JdbcTemplate jdbc;

    public JdbcA2aTaskAuthorizationStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Called only when the signed A2A protocol is enabled, so migration mistakes fail at startup. */
    public void verifySchema() {
        jdbc.queryForList("SELECT nonce FROM a2a_request_nonce WHERE 1 = 0");
        jdbc.queryForList("SELECT task_id FROM a2a_task_owner WHERE 1 = 0");
        jdbc.queryForList("SELECT task_id FROM a2a_task_execution_reservation WHERE 1 = 0");
    }

    @Override
    public boolean consumeNonce(String nonce, long expiresAtEpochSecond, long nowEpochSecond) {
        jdbc.update("DELETE FROM a2a_request_nonce WHERE expires_at <= ?", nowEpochSecond);
        try {
            jdbc.update("INSERT INTO a2a_request_nonce (nonce, expires_at) VALUES (?, ?)",
                    nonce, expiresAtEpochSecond);
            return true;
        } catch (DuplicateKeyException replay) {
            return false;
        }
    }

    @Override
    public boolean bindOrVerifyTaskOwner(String taskId, String actorId, String callerService,
                                         String a2aContextId, String runId, String sessionId,
                                         boolean sending) {
        if (sending) {
            try {
                jdbc.update("INSERT INTO a2a_task_owner "
                                + "(task_id, actor_id, caller_service, context_id, run_id, session_id) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        taskId, Long.parseLong(actorId), callerService, a2aContextId, runId, sessionId);
            } catch (DuplicateKeyException existing) {
                // An exact same-run retry/continuation may reuse the task; no field is replaced.
            }
        }
        List<TaskOwner> owners = jdbc.query("SELECT actor_id, caller_service, context_id, run_id, session_id "
                        + "FROM a2a_task_owner WHERE task_id = ?",
                (rs, row) -> new TaskOwner(Long.toString(rs.getLong("actor_id")),
                        rs.getString("caller_service"), rs.getString("context_id"),
                        rs.getString("run_id"), rs.getString("session_id")), taskId);
        if (owners.size() != 1) return false;
        TaskOwner owner = owners.get(0);
        return owner.actorId.equals(actorId)
                && owner.callerService.equals(callerService)
                && (!sending || Objects.equals(owner.contextId, a2aContextId))
                && owner.runId.equals(runId)
                && owner.sessionId.equals(sessionId);
    }

    @Override
    public boolean reserveTask(String taskId, String actorId, String nonce,
                               long expiresAtEpochSecond, long nowEpochSecond) {
        try {
            jdbc.update("INSERT INTO a2a_task_execution_reservation "
                            + "(task_id, actor_id, request_nonce, reserved_until) VALUES (?, ?, ?, ?)",
                    taskId, Long.parseLong(actorId), nonce, expiresAtEpochSecond);
            return true;
        } catch (DuplicateKeyException activeOrExpired) {
            return jdbc.update("UPDATE a2a_task_execution_reservation "
                            + "SET actor_id = ?, request_nonce = ?, reserved_until = ? "
                            + "WHERE task_id = ? AND reserved_until <= ?",
                    Long.parseLong(actorId), nonce, expiresAtEpochSecond, taskId, nowEpochSecond) == 1;
        }
    }

    @Override
    public void releaseTaskReservation(String taskId, String nonce) {
        jdbc.update("DELETE FROM a2a_task_execution_reservation WHERE task_id = ? AND request_nonce = ?",
                taskId, nonce);
    }

    private record TaskOwner(String actorId, String callerService, String contextId,
                             String runId, String sessionId) { }
}
