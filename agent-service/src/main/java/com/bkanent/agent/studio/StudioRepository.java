package com.bkanent.agent.studio;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.Supplier;

@Repository
public class StudioRepository {
    final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public StudioRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) { this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.transactions.setTimeout(45); }
    public <T> T transaction(Supplier<T> action) { return transactions.execute(status -> action.get()); }
    public Map<String, Object> work(String owner, String id, boolean lock) {
        var rows = jdbc.queryForList("SELECT * FROM studio_work WHERE work_id=? AND owner_id=?" + (lock ? " FOR UPDATE" : ""), id, owner);
        if (rows.isEmpty()) throw StudioException.missing();
        return rows.get(0);
    }
    public Map<String, Object> command(String owner, String workId, String requestId) {
        var rows = jdbc.queryForList("SELECT * FROM studio_command WHERE work_id=? AND owner_id=? AND request_id=?", workId, owner, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public void touch(String workId) { jdbc.update("UPDATE studio_work SET revision=revision+1,updated_at_ms=? WHERE work_id=?", System.currentTimeMillis(), workId); }
}
