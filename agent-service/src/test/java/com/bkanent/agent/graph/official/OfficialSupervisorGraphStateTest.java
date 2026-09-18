package com.bkanent.agent.graph.official;

import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.WorkflowStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class OfficialSupervisorGraphStateTest {

    @Test
    void usesReplaceForExecutionPositionAndAppendForEventCollections() {
        Map<String, com.alibaba.cloud.ai.graph.KeyStrategy> strategies =
                new OfficialSupervisorGraphSchema().keyStrategyFactory().apply();

        assertInstanceOf(com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy.class,
                strategies.get(OfficialSupervisorGraphKeys.CURRENT_NODE));
        assertInstanceOf(com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy.class,
                strategies.get(OfficialSupervisorGraphKeys.NEXT_NODE));
        assertInstanceOf(com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy.class,
                strategies.get(OfficialSupervisorGraphKeys.EVENT_REFERENCES));
        assertInstanceOf(com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy.class,
                strategies.get(OfficialSupervisorGraphKeys.ARTIFACT_IDS));
    }

    @Test
    void appendsOnlyNewArtifactsAndHandoffs() {
        Map<String, Object> existingHandoff = Map.of("toAgent", "listing-agent");
        Map<String, Object> newHandoff = Map.of("toAgent", "trade-agent");
        assertThat(OfficialGraphStateAdapters.delta(List.of("a1"), List.of("a1", "a2")))
                .containsExactly("a2");
        assertThat(OfficialGraphStateAdapters.delta(List.of(existingHandoff),
                List.of(existingHandoff, newHandoff))).containsExactly(newHandoff);
    }

    @Test
    void rejectsNullCollectionWhenCalculatingDelta() {
        assertThat(OfficialGraphStateAdapters.delta(List.of("a1"), null)).isEmpty();
    }

    @Test
    void createsDeltaMapWithoutDuplicatingAppendOnlyFields() {
        SupervisorGraphState previous = SupervisorGraphState.initialize(
                "session-1", "task-1", "trace-1", "user-1", "hello");
        SupervisorGraphState next = new SupervisorGraphState(
                previous.sessionId(), previous.taskId(), previous.traceId(), previous.userId(),
                previous.userMessage(), WorkflowStatus.RUNNING, Map.of(), "intent", "listing",
                "single", false, false, "listing-agent", List.of(), List.of("artifact-1"),
                List.of(Map.of("toAgent", "listing-agent")), null);

        Map<String, Object> updates = OfficialGraphStateAdapters.toDeltaMap(previous, next);

        assertThat(updates.get(OfficialSupervisorGraphKeys.ARTIFACT_IDS))
                .isEqualTo(List.of("artifact-1"));
        assertThat(updates.get(OfficialSupervisorGraphKeys.HANDOFF_HISTORY))
                .isEqualTo(List.of(Map.of("toAgent", "listing-agent")));
    }
}
