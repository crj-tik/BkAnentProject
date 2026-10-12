package com.bkanent.memory.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.*;

class SystemConstraintBootstrapRunnerTest {
    @Test
    void disabledDoesNotReadOrWriteAndEnabledOnlyAddsMissing() throws Exception {
        var f = new MemoryRagTestSupport();
        new SystemConstraintBootstrapRunner(f.constraints, false).run(new DefaultApplicationArguments());
        assertEquals(0, f.count("system_constraint_memory"));
        // Wrap the standalone test service in the transaction that Spring's proxy supplies.
        var transactional = new SystemConstraintMemoryService(f.jdbc, f.projection) {
            @Override public boolean bootstrapMissing(com.bkanent.common.agent.SystemConstraintRecord record) { return f.tx.execute(tx -> super.bootstrapMissing(record)); }
        };
        var runner = new SystemConstraintBootstrapRunner(transactional, true);
        runner.run(new DefaultApplicationArguments());
        int records = f.count("system_constraint_memory");
        assertTrue(records > 0);
        assertEquals(records, f.count("memory_rag_outbox"));
        runner.run(new DefaultApplicationArguments());
        assertEquals(records, f.count("memory_rag_outbox"));
    }
}
