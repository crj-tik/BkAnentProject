package com.bkanent.agent.orchestration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
@ConfigurationProperties("agent.supervisor.orchestration")
public class SupervisorOrchestrationProperties {
    private int maxRounds = 12;
    private int maxToolCalls = 24;
    private int maxConcurrency = 4;
    private int modelRetries = 2;
    private long modelTimeoutMs = 30_000;
    private long toolTimeoutMs = 120_000;
    private int maxQueuedCalls = 128;
    private Set<String> approvalCapabilities = Set.of();
    private boolean accepting = true;
    public int getMaxRounds() { return maxRounds; }
    public void setMaxRounds(int value) { maxRounds = value; }
    public int getMaxToolCalls() { return maxToolCalls; }
    public void setMaxToolCalls(int value) { maxToolCalls = value; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int value) { maxConcurrency = value; }
    public int getModelRetries() { return modelRetries; }
    public void setModelRetries(int value) { modelRetries = value; }
    public long getModelTimeoutMs() { return modelTimeoutMs; }
    public void setModelTimeoutMs(long value) { modelTimeoutMs = value; }
    public long getToolTimeoutMs() { return toolTimeoutMs; }
    public void setToolTimeoutMs(long value) { toolTimeoutMs = value; }
    public int getMaxQueuedCalls() { return maxQueuedCalls; }
    public void setMaxQueuedCalls(int value) { maxQueuedCalls = value; }
    public Set<String> getApprovalCapabilities() { return approvalCapabilities; }
    public void setApprovalCapabilities(Set<String> value) { approvalCapabilities = Set.copyOf(value); }
    public boolean isAccepting() { return accepting; }
    public void setAccepting(boolean value) { accepting = value; }
}
