package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the official Graph pause/resume contract independently of providers. */
class OfficialSupervisorGraphApprovalIntegrationTest {

    @Test
    void pausesBeforeProtectedNodeAndResumesToTheApprovedBranch() throws Exception {
        CompiledGraph graph = buildGraph();
        RunnableConfig config = RunnableConfig.builder().threadId("approval-task-1").build();

        OverAllState waiting = graph.invoke(Map.of("status", "RUNNING"), config).orElseThrow();

        assertThat(waiting.value("status", String.class).orElseThrow()).isEqualTo("WAITING");
        assertThat(waiting.value("protectedCalls", Integer.class).orElse(0)).isZero();

        StateSnapshot snapshot = graph.lastStateOf(config).orElseThrow();
        RunnableConfig resumed = graph.updateState(
                snapshot.config(), Map.of("decision", "approve")).withResume();
        OverAllState completed = graph.invoke(Map.of(), resumed).orElseThrow();

        assertThat(completed.value("status", String.class).orElseThrow()).isEqualTo("COMPLETED");
        assertThat(completed.value("protectedCalls", Integer.class).orElse(0)).isEqualTo(1);
    }

    private CompiledGraph buildGraph() throws Exception {
        var strategies = new KeyStrategyFactoryBuilder()
                .addStrategy("status", new ReplaceStrategy())
                .addStrategy("decision", new ReplaceStrategy())
                .addStrategy("protectedCalls", new ReplaceStrategy())
                .build();
        StateGraph stateGraph = new StateGraph("approval-test", strategies);
        stateGraph.addNode("approval", node(state -> Map.of("status", "WAITING")));
        stateGraph.addNode("resume", node(state -> Map.of("status", "RESUMED")));
        stateGraph.addNode("protected", node(state -> Map.of(
                "status", "COMPLETED", "protectedCalls", 1)));
        stateGraph.addNode("cancel", node(state -> Map.of(
                "status", "CANCELED", "protectedCalls", 0)));
        stateGraph.addEdge(StateGraph.START, "approval");
        stateGraph.addEdge("approval", "resume");
        stateGraph.addConditionalEdges("resume", com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async(state ->
                        "approve".equals(state.value("decision", "")) ? "protected" : "cancel"),
                Map.of("protected", "protected", "cancel", "cancel"));
        stateGraph.addEdge("protected", StateGraph.END);
        stateGraph.addEdge("cancel", StateGraph.END);
        return stateGraph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                .interruptAfter("approval")
                .build());
    }

    private AsyncNodeAction node(NodeAction action) {
        return AsyncNodeAction.node_async(action);
    }
}
