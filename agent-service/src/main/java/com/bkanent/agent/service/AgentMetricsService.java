package com.bkanent.agent.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class AgentMetricsService {

    private final MeterRegistry meterRegistry;

    public AgentMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordGraphSubgraph(String subgraph, String status, long durationMs) {
        Timer.builder("bk.agent.graph.subgraph.duration")
                .tag("subgraph", subgraph)
                .tag("status", status)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
        Counter.builder("bk.agent.graph.subgraph.count")
                .tag("subgraph", subgraph)
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }

    public void recordAsyncTask(String mode, String status, long durationMs) {
        Timer.builder("bk.agent.async.task.duration")
                .tag("mode", mode)
                .tag("status", status)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
        Counter.builder("bk.agent.async.task.count")
                .tag("mode", mode)
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }

    public void recordAsyncWorkflow(String status, long durationMs) {
        Timer.builder("bk.agent.async.workflow.duration")
                .tag("status", status)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
        Counter.builder("bk.agent.async.workflow.count")
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }

    public void recordPermissionDenied(String action) {
        Counter.builder("bk.agent.security.permission.denied")
                .tag("action", action == null ? "unknown" : action)
                .register(meterRegistry)
                .increment();
    }
}
