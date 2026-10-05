package com.bkanent.agent.orchestration;

import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
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
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("sql/migrations/20261004_supervisor_orchestration.sql"))) root = root.getParent();
        for (String sql : Files.readString(root.resolve("sql/migrations/20261004_supervisor_orchestration.sql")).replaceAll("(?m)^--.*$", "").split(";"))
            if (!sql.isBlank()) jdbc.execute(sql);
        for (String sql : Files.readString(root.resolve("sql/migrations/20261004_supervisor_run_control.sql")).replaceAll("(?m)^--.*$", "").split(";"))
            if (!sql.isBlank()) jdbc.execute(sql);
        return new OrchestrationStore(jdbc, mapper);
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
