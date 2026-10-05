package com.bkanent.agent.workflow;

import com.bkanent.agent.entity.AgentWorkflowCheckpointEntity;
import com.bkanent.agent.mapper.AgentWorkflowCheckpointMapper;
import com.bkanent.common.agent.WorkflowStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DbGraphCheckpointStoreTest {

    @Test
    void readsOfficialCheckpointEnvelopeWithoutLosingOwnershipOrStatus() {
        AgentWorkflowCheckpointMapper mapper = mock(AgentWorkflowCheckpointMapper.class);
        AgentWorkflowCheckpointEntity entity = new AgentWorkflowCheckpointEntity();
        entity.setSnapshotJson("""
                {"graphName":"historical-supervisor","nodeId":"fail","nextNodeId":"__END__",
                 "state":{"sessionId":"session-1","taskId":"task-1","traceId":"trace-1",
                 "userId":"2","userMessage":"访谈","workflowStatus":"FAILED",
                 "selectedAgentId":"interview-agent","sharedContext":{},"artifactIds":[]}}
                """);
        when(mapper.selectOne(any())).thenReturn(entity);

        SupervisorWorkflowState state = new DbGraphCheckpointStore(mapper, new ObjectMapper())
                .load("task-1").orElseThrow();

        assertEquals("2", state.userId());
        assertEquals("task-1", state.taskId());
        assertEquals("session-1", state.sessionId());
        assertEquals(WorkflowStatus.FAILED, state.workflowStatus());
        assertEquals("interview-agent", state.selectedAgentId());
    }

    @Test
    void continuesToReadLegacyCheckpointRecords() {
        AgentWorkflowCheckpointMapper mapper = mock(AgentWorkflowCheckpointMapper.class);
        AgentWorkflowCheckpointEntity entity = new AgentWorkflowCheckpointEntity();
        entity.setSnapshotJson("""
                {"sessionId":"session-1","taskId":"task-1","userId":"2",
                 "workflowStatus":"RUNNING","sharedContext":{},"artifactIds":[],"handoffHistory":[]}
                """);
        when(mapper.selectOne(any())).thenReturn(entity);

        SupervisorWorkflowState state = new DbGraphCheckpointStore(mapper, new ObjectMapper())
                .load("task-1").orElseThrow();

        assertEquals("2", state.userId());
        assertEquals(WorkflowStatus.RUNNING, state.workflowStatus());
    }
}
