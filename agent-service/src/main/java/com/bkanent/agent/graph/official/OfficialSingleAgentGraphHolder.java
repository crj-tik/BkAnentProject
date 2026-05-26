package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import org.springframework.stereotype.Component;

@Component
public class OfficialSingleAgentGraphHolder {

    private final CompiledGraph compiledGraph;

    public OfficialSingleAgentGraphHolder(OfficialSingleAgentGraphFactory factory) throws Exception {
        this.compiledGraph = factory.create();
    }

    public CompiledGraph compiledGraph() {
        return compiledGraph;
    }
}
