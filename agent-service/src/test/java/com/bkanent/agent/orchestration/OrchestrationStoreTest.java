package com.bkanent.agent.orchestration;

import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;

class OrchestrationStoreTest {
    @Test
    void rejectedCallCannotBeClaimedAndExecutingCallCannotBeRejected() throws Exception {
        var store = database(new ObjectMapper());
        store.register("run", new SupervisorTaskRequest("session", "1", "run", "trace", "request", Map.of(), "api", false));
        store.prepare("run", "unsent", "local:search", "{}");
        assertThat(store.reject("run", "unsent", "EXECUTOR_BUSY: 未执行")).isTrue();
        assertThat(store.claim("run", "unsent")).isFalse();
        store.unknown("run", "unsent");
        assertThat(store.find("run", "unsent").status()).isEqualTo("REJECTED");
        store.prepare("run", "sent", "local:search", "{}");
        assertThat(store.claim("run", "sent")).isTrue();
        assertThat(store.reject("run", "sent", "unsent")).isFalse();
        store.unknown("run", "sent");
        assertThat(store.find("run", "sent").status()).isEqualTo("OUTCOME_UNKNOWN");
    }

    static OrchestrationStore database(ObjectMapper mapper) throws Exception {
        JdbcTemplate jdbc = fixtureJdbc();
        applyProductionMigrations(jdbc);
        return new OrchestrationStore(jdbc, mapper);
    }

    private static JdbcTemplate fixtureJdbc() {
        // H2 USE selects a schema, not a MySQL database. JdbcTemplate opens a new
        // connection per statement, so initialize the fixed target on every connection.
        return new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1"
                        + ";INIT=CREATE SCHEMA IF NOT EXISTS bk_agent\\;SET SCHEMA bk_agent", "sa", ""));
    }

    private static void applyProductionMigrations(JdbcTemplate jdbc) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("sql/migrations/20261004_supervisor_orchestration.sql"))) root = root.getParent();
        for (String file : List.of("20261004_supervisor_orchestration.sql", "20261004_supervisor_run_control.sql")) {
            H2MigrationFixture.apply(jdbc, Files.readString(root.resolve("sql/migrations/" + file)));
        }
    }

    /** Test-only adapter for the fixed, checked-in MySQL migrations (LR-42/KI-87). */
    private static final class H2MigrationFixture {
        private static final Pattern CREATE = Pattern.compile(
                "(?s)CREATE TABLE IF NOT EXISTS (agent_orchestration_run|agent_tool_invocation|agent_skill_snapshot) \\(.+\\)");
        private static final Pattern CONDITIONAL_ALTER = Pattern.compile(
                "SET @bk_ddl = IF\\(EXISTS\\(SELECT 1 FROM information_schema\\.columns WHERE table_schema='bk_agent' "
                        + "AND table_name='(agent_orchestration_run|agent_tool_invocation)' "
                        + "AND column_name='(cancel_requested|execution_metadata_json)'\\), 'SELECT 1', "
                        + "'(ALTER TABLE bk_agent\\.\\1 ADD COLUMN \\2 (?:INTEGER NOT NULL DEFAULT 0|LONGTEXT))'\\)");

        static void apply(JdbcTemplate jdbc, String script) {
            String[] statements = script.replaceAll("(?m)^--.*$", "").split(";");
            for (int i = 0; i < statements.length; i++) {
                String sql = statements[i].strip();
                if (sql.isEmpty()) continue;
                if (sql.equals("USE bk_agent") || CREATE.matcher(sql).matches()) {
                    jdbc.execute(sql); // Execute real production DDL and USE; do not drop them.
                    continue;
                }
                var alter = CONDITIONAL_ALTER.matcher(sql);
                if (!alter.matches() || i + 3 >= statements.length
                        || !statements[i + 1].strip().equals("PREPARE bk_statement FROM @bk_ddl")
                        || !statements[i + 2].strip().equals("EXECUTE bk_statement")
                        || !statements[i + 3].strip().equals("DEALLOCATE PREPARE bk_statement")) {
                    throw new IllegalArgumentException("Unsupported orchestration fixture SQL: " + sql);
                }
                // H2 lacks MySQL's prepared-DDL protocol. Preserve its existence guard
                // and execute the ALTER text embedded in the real migration, not a copy.
                Integer columns = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                                + "WHERE table_schema='BK_AGENT' AND table_name=? AND column_name=?", Integer.class,
                        alter.group(1).toUpperCase(Locale.ROOT), alter.group(2).toUpperCase(Locale.ROOT));
                if (columns == 0) jdbc.execute(alter.group(3));
                i += 3;
            }
        }
    }

    @Test
    void productionMigrationFixtureKeepsTargetSchemaColumnsAndIndexesOnRepeatedRun() throws Exception {
        JdbcTemplate jdbc = fixtureJdbc();
        applyProductionMigrations(jdbc);
        applyProductionMigrations(jdbc);
        // Each query also uses a fresh connection; USE on an earlier one is insufficient.
        assertThat(jdbc.queryForObject("SELECT CURRENT_SCHEMA()", String.class)).isEqualTo("BK_AGENT");
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='BK_AGENT' ORDER BY table_name", String.class))
                .containsExactly("AGENT_ORCHESTRATION_RUN", "AGENT_SKILL_SNAPSHOT", "AGENT_TOOL_INVOCATION");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema='BK_AGENT' AND table_name='AGENT_ORCHESTRATION_RUN' ORDER BY ordinal_position", String.class))
                .containsExactly("RUN_ID", "USER_ID", "REQUEST_JSON", "LEASE_OWNER", "LEASE_UNTIL_MS", "CANCEL_REQUESTED");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema='BK_AGENT' AND table_name='AGENT_TOOL_INVOCATION' ORDER BY ordinal_position", String.class))
                .containsExactly("RUN_ID", "CALL_ID", "CAPABILITY_ID", "ARGUMENTS_HASH", "ARGUMENTS_JSON", "STATUS", "RESULT_JSON", "REMOTE_ASSOCIATION_JSON", "UPDATED_AT_MS", "EXECUTION_METADATA_JSON");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema='BK_AGENT' AND table_name='AGENT_SKILL_SNAPSHOT' ORDER BY ordinal_position", String.class))
                .containsExactly("RUN_ID", "SNAPSHOT_ID", "OWNER", "SKILL_NAME", "SKILL_VERSION", "CONTENT_HASH", "SNAPSHOT_JSON");
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.indexes WHERE table_schema='BK_AGENT' AND index_type_name='PRIMARY KEY' ORDER BY table_name", String.class))
                .containsExactly("AGENT_ORCHESTRATION_RUN", "AGENT_SKILL_SNAPSHOT", "AGENT_TOOL_INVOCATION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.indexes WHERE table_schema='BK_AGENT'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='PUBLIC'", Integer.class)).isZero();
        jdbc.update("INSERT INTO agent_orchestration_run(run_id,user_id,request_json) VALUES ('fixture','1','{}')");
        assertThat(jdbc.queryForObject("SELECT cancel_requested FROM agent_orchestration_run WHERE run_id='fixture'", Integer.class)).isZero();
        jdbc.update("INSERT INTO agent_tool_invocation(run_id,call_id,capability_id,arguments_hash,arguments_json,status,updated_at_ms,execution_metadata_json) VALUES ('fixture','call','local:search','hash','{}','PENDING',0,'metadata')");
        assertThat(jdbc.queryForObject("SELECT execution_metadata_json FROM agent_tool_invocation WHERE run_id='fixture'", String.class)).isEqualTo("metadata");
    }

    @Test
    void productionMigrationFixtureRejectsUnknownSqlTargetsAndIncompletePreparedDdl() throws Exception {
        JdbcTemplate jdbc = fixtureJdbc();
        for (String sql : List.of("SELECT 1;", "USE other_database;", "CREATE TABLE unexpected (id INTEGER);",
                "ALTER TABLE bk_agent.agent_orchestration_run ADD COLUMN surprise INTEGER;", "PREPARE bk_statement FROM @bk_ddl;")) {
            assertThatThrownBy(() -> H2MigrationFixture.apply(jdbc, sql))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported orchestration fixture SQL");
        }
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("sql/migrations/20261004_supervisor_run_control.sql"))) root = root.getParent();
        String control = Files.readString(root.resolve("sql/migrations/20261004_supervisor_run_control.sql"));
        for (String invalid : List.of(control.replace("bk_agent", "other_database"),
                control.replace("EXECUTE bk_statement;", "SELECT 1;"),
                control.replace("DEALLOCATE PREPARE bk_statement;", ""))) {
            assertThatThrownBy(() -> H2MigrationFixture.apply(jdbc, invalid))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported orchestration fixture SQL");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='BK_AGENT'", Integer.class)).isZero();
    }

    @Test
    void sqlEnforcesSingleOwnerLeaseAndCallIdentityAndReusesCompletedResult() throws Exception {
        var store = database(new ObjectMapper());
        var request = new SupervisorTaskRequest("session", "1", "run", "trace", "request", Map.of(), "api", false);
        store.register("run", request);
        assertThat(store.lease("run", "worker-one", 10000)).isTrue();
        assertThat(store.lease("run", "worker-two", 10000)).isFalse();
        assertThatThrownBy(() -> store.assertOwner("run", "2")).hasMessage("CONTINUATION_OWNER_MISMATCH");
        var call = store.prepare("run", "call", "local:search", "{\"b\":2,\"a\":1}");
        assertThat(store.prepare("run", "call", "local:search", "{\"a\":1,\"b\":2}")).isEqualTo(call);
        assertThatThrownBy(() -> store.prepare("run", "call", "local:search", "{\"a\":3}")).hasMessage("TOOL_CALL_ID_CONFLICT");
        assertThat(store.claim("run", "call")).isTrue();
        assertThat(store.claim("run", "call")).isFalse();
        store.complete("run", "call", "actual result");
        assertThat(store.find("run", "call").result()).isEqualTo("actual result");
        store.release("run", "worker-two");
        assertThat(store.lease("run", "worker-two", 10000)).isFalse();
        store.release("run", "worker-one");
        assertThat(store.lease("run", "worker-two", 10000)).isTrue();
    }
}
