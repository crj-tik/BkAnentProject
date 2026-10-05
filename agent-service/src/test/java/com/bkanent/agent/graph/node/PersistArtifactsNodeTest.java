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
    void completedPrimaryRecoveryCreatesMissingDerivedArtifactWithCallMetadata() {
        TaskArtifactStore store = mock(TaskArtifactStore.class);
        SessionStreamService events = mock(SessionStreamService.class);
        PersistArtifactsNode node = new PersistArtifactsNode(store, events);
        when(store.findBySourceArtifactId("run", "session", "marketing-agent", "remote"))
                .thenReturn(Optional.of("primary"));
        when(store.findBySourceArtifactId("run", "session", "marketing-agent", "remote:copy_draft_body"))
                .thenReturn(Optional.empty());
        when(store.save(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("derived");
        var response = new AgentTaskInvokeResponse("session", "child", "marketing-agent", "COMPLETED",
                Map.of("contentType", "copy_draft", "draftText", "实际草稿"), List.of("remote"), List.of(), "done", "trace");
        var facts = Map.<String, Object>of("mode", "EXPLICIT_SKILL", "capabilityId", "a2a:marketing-agent", "callId", "call",
                "skill", Map.of("name", "demo", "version", "1"));
        assertThat(node.persistSingle("run", "session", "marketing-agent", "1", "trace", response, facts))
                .containsExactly("remote", "primary", "derived");
        var metadata = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(store).save(eq("run"), eq("session"), eq("marketing-agent"), eq("copy_draft_body"), eq(1), any(), metadata.capture(), eq("trace"));
        assertThat(metadata.getValue()).containsAllEntriesOf(facts);
        var event = org.mockito.ArgumentCaptor.forClass(com.bkanent.common.agent.SessionStreamEvent.class);
        verify(events).publish(event.capture());
        assertThat(event.getValue().metadata()).containsAllEntriesOf(facts).containsEntry("userId", "1");
    }

    @Test
    void eventOutageDoesNotLoseCompletedPrimaryOrDerivedArtifact() {
        TaskArtifactStore store = mock(TaskArtifactStore.class);
        SessionStreamService events = mock(SessionStreamService.class);
        when(store.findBySourceArtifactId(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(store.save(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("primary", "derived");
        org.mockito.Mockito.doThrow(new IllegalStateException("events unavailable")).when(events).publish(any());
        var response = new AgentTaskInvokeResponse("session", "child", "marketing-agent", "COMPLETED",
                Map.of("contentType", "copy_draft", "draftText", "draft"), List.of("remote"), List.of(), "done", "trace");
        assertThat(new PersistArtifactsNode(store, events).persistSingle("run", "session", "marketing-agent", "1", "trace", response))
                .containsExactly("remote", "primary", "derived");
        verify(store, org.mockito.Mockito.times(2)).save(any(), any(), any(), any(), any(), any(), any(), any());
    }

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

}
