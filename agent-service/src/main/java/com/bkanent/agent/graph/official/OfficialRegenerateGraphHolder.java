package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import org.springframework.stereotype.Component;

@Component
public class OfficialRegenerateGraphHolder {

    private final CompiledGraph compiledGraph;

    public OfficialRegenerateGraphHolder(OfficialRegenerateGraphFactory factory) throws Exception {
        this.compiledGraph = factory.create();
    }

    public CompiledGraph compiledGraph() {
        return compiledGraph;
    }
}
