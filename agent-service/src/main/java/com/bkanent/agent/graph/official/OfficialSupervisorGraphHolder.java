package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import org.springframework.stereotype.Component;

@Component
public class OfficialSupervisorGraphHolder {

    private final CompiledGraph compiledGraph;

    public OfficialSupervisorGraphHolder(OfficialSupervisorGraphFactory factory) throws Exception {
        this.compiledGraph = factory.create();
    }

    public CompiledGraph compiledGraph() {
        return compiledGraph;
    }
}
