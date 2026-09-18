package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.NodeAggregationStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncMultiCommandAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.MultiCommand;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies native Graph fan-out/fan-in instead of service-managed futures. */
class OfficialSupervisorGraphParallelIntegrationTest {

    @Test
    void fansOutToBothBranchesAndWaitsForAllOfBeforeFanIn() throws Exception {
        CompiledGraph graph = buildGraph();
        long startedAt = System.currentTimeMillis();

        OverAllState output = graph.invoke(
                Map.of("status", "RUNNING"),
                RunnableConfig.builder()
                        .threadId("parallel-task-1")
                        .defaultParallelAggregationStrategy(NodeAggregationStrategy.ALL_OF)
                        .build()
        ).orElseThrow();

        long elapsed = System.currentTimeMillis() - startedAt;
        assertThat(output.value("status", String.class).orElseThrow()).isEqualTo("COMPLETED");
        assertThat(output.value("results", List.class).orElseThrow())
                .containsExactlyInAnyOrder("listing", "marketing");
        assertThat(elapsed).isLessThan(350L);
    }

    private CompiledGraph buildGraph() throws Exception {
        var strategies = new KeyStrategyFactoryBuilder()
                .addStrategy("status", new ReplaceStrategy())
                .addStrategy("results", new AppendStrategy())
                .build();
        StateGraph stateGraph = new StateGraph("parallel-test", strategies);
        stateGraph.addNode("fanout", node(state -> Map.of()));
        stateGraph.addNode("listing", node(state -> branchResult("listing")));
        stateGraph.addNode("marketing", node(state -> branchResult("marketing")));
        stateGraph.addNode("merge", node(state -> Map.of(
                "status", "COMPLETED",
                "mergedCount", state.value("results", List.class).orElseThrow().size())));
        stateGraph.addEdge(StateGraph.START, "fanout");
        stateGraph.addParallelConditionalEdges(
                "fanout",
                AsyncMultiCommandAction.node_async((state, config) ->
                        new MultiCommand(List.of("listing", "marketing"))),
                Map.of("listing", "listing", "marketing", "marketing")
        );
        stateGraph.addEdge(List.of("listing", "marketing"), "merge");
        stateGraph.addEdge("merge", StateGraph.END);
        return stateGraph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                .build());
    }

    private Map<String, Object> branchResult(String domain) throws InterruptedException {
        Thread.sleep(120L);
        return Map.of("results", List.of(domain));
    }

    private AsyncNodeAction node(NodeAction action) {
        return AsyncNodeAction.node_async(action);
    }
}
