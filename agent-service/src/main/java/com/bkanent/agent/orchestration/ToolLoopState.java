package com.bkanent.agent.orchestration;

import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.common.agent.ApprovalRequest;
import com.bkanent.common.skill.runtime.SkillExecutionSnapshot;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import java.util.ArrayList;
import java.util.List;

/** Generic execution facts; deliberately contains no domain route or skill-step progress. */
public class ToolLoopState {
    public String runnerVersion = "llm-tools-v1";
    public SupervisorTaskRequest request;
    public String runId;
    public String sessionId;
    public String traceId;
    public String userId;
    public String mode;
    public String status = "RUNNING";
    public boolean allowMcp;
    public SkillExecutionSnapshot skillSnapshot;
    public List<StoredModelMessage> messages = new ArrayList<>();
    public List<AssistantMessage.ToolCall> pendingCalls = new ArrayList<>();
    public List<ToolResponseMessage.ToolResponse> results = new ArrayList<>();
    public List<String> artifactIds = new ArrayList<>();
    public List<String> visibleCapabilityIds = new ArrayList<>();
    public java.util.Map<String, String> visibleCapabilityVersions = new java.util.LinkedHashMap<>();
    public int rounds;
    public int toolCalls;
    public int maxRounds;
    public int maxToolCalls;
    public String route;
    public String question;
    public List<String> missingFields = new ArrayList<>();
    public ApprovalRequest pendingApproval;
    public String approvedBatchHash;
    public String finalAnswer = "";
    public String errorCode;
    public String leaseToken;
    public String lastInputId;
    public List<String> uncertainCallIds = new ArrayList<>();
}
