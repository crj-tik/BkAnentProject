package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import org.springframework.stereotype.Component;

@Component
public class OfficialCompletionGraphHolder {

    private final CompiledGraph compiledGraph;

    public OfficialCompletionGraphHolder(OfficialCompletionGraphFactory factory) throws Exception {
        this.compiledGraph = factory.create();
    }

    public CompiledGraph compiledGraph() {
        return compiledGraph;
    }
}
