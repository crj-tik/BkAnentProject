package com.bkanent.agent.graph.node;

import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.agent.workflow.TaskArtifactStore;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistArtifactsNodeTest {

    @Test
    void reusesArtifactForRepeatedOfficialTaskIdentity() {
        TaskArtifactStore store = mock(TaskArtifactStore.class);
        SessionStreamService streamService = mock(SessionStreamService.class);
        PersistArtifactsNode node = new PersistArtifactsNode(store, streamService);
        AgentTaskInvokeResponse response = new AgentTaskInvokeResponse(
                "session-1", "task-1", "listing-agent", "COMPLETED",
                Map.of("contentType", "listing_result", "value", "same"),
                List.of("remote-artifact-1"), List.of(), "done", "trace-1");
        when(store.findBySourceArtifactId("task-1", "session-1", "listing-agent", "remote-artifact-1"))
                .thenReturn(Optional.empty(), Optional.of("local-artifact-1"));
        when(store.save(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("local-artifact-1");

        List<String> first = node.persistSingle("task-1", "session-1", "listing-agent", "user-1", "trace-1", response);
        List<String> second = node.persistSingle("task-1", "session-1", "listing-agent", "user-1", "trace-1", response);

        assertThat(first).containsExactly("remote-artifact-1", "local-artifact-1");
        assertThat(second).containsExactly("remote-artifact-1", "local-artifact-1");
        verify(store).save(eq("task-1"), eq("session-1"), eq("listing-agent"), eq("listing_result"),
                eq(1), any(), any(), eq("trace-1"));
    }

    @Test
    void reusesParallelArtifactForRepeatedOfficialIdentity() {
        TaskArtifactStore store = mock(TaskArtifactStore.class);
        SessionStreamService streamService = mock(SessionStreamService.class);
        PersistParallelArtifactsNode node = new PersistParallelArtifactsNode(store, streamService);
        AgentTaskInvokeResponse response = new AgentTaskInvokeResponse(
                "session-1", "task-1", "parallel-supervisor", "COMPLETED",
                Map.of("contentType", "parallel_result", "value", "same"),
                List.of("remote-artifact-1"), List.of(), "done", "trace-1");
        when(store.findBySourceArtifactId("task-1", "session-1", "parallel-supervisor", "remote-artifact-1"))
                .thenReturn(Optional.empty(), Optional.of("local-artifact-1"));
        when(store.save(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("local-artifact-1");

        List<String> first = node.persist("task-1", "session-1", "user-1", "trace-1",
                List.of("listing", "marketing"), response);
        List<String> second = node.persist("task-1", "session-1", "user-1", "trace-1",
                List.of("listing", "marketing"), response);

        assertThat(first).containsExactly("remote-artifact-1", "local-artifact-1");
        assertThat(second).containsExactly("remote-artifact-1", "local-artifact-1");
        verify(store).save(eq("task-1"), eq("session-1"), eq("parallel-supervisor"), eq("parallel_result"),
                eq(1), any(), any(), eq("trace-1"));
    }
}
