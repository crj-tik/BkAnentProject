package com.bkanent.agent.graph.official;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.agent.entity.AgentWorkflowApprovalClaimEntity;
import com.bkanent.agent.mapper.AgentWorkflowApprovalClaimMapper;
import com.bkanent.common.agent.ApprovalCallbackRequest;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Cross-instance claim store. The unique approval id is the serialization
 * point; JVM locks remain only as a local latency optimization.
 */
@Component
public class ApprovalResumeClaimStore {

    private static final String PROCESSING = "PROCESSING";
    private static final String COMPLETED = "COMPLETED";
    private static final String FAILED = "FAILED";

    private final AgentWorkflowApprovalClaimMapper claimMapper;
    private final ObjectMapper objectMapper;

    public ApprovalResumeClaimStore(AgentWorkflowApprovalClaimMapper claimMapper,
                                    ObjectMapper objectMapper) {
        this.claimMapper = claimMapper;
        this.objectMapper = objectMapper;
    }

    public ClaimResult claim(ApprovalCallbackRequest request) {
        AgentWorkflowApprovalClaimEntity candidate = new AgentWorkflowApprovalClaimEntity();
        candidate.setApprovalId(request.approvalId());
        candidate.setTaskId(request.taskId());
        candidate.setSessionId(request.sessionId());
        candidate.setApprovalVersion(request.approvalVersion());
        candidate.setDecisionStatus(PROCESSING);
        try {
            claimMapper.insert(candidate);
            return ClaimResult.claimed();
        } catch (DataIntegrityViolationException exception) {
            AgentWorkflowApprovalClaimEntity existing = find(request.approvalId());
            if (existing == null) {
                throw new IllegalStateException("Approval callback claim conflicted but cannot be read", exception);
            }
            if (COMPLETED.equals(existing.getDecisionStatus())
                    && existing.getResultJson() != null) {
                try {
                    return ClaimResult.replay(objectMapper.readValue(
                            existing.getResultJson(), SupervisorTaskResponse.class));
                } catch (JsonProcessingException readException) {
                    throw new IllegalStateException("Stored approval callback result is invalid", readException);
                }
            }
            if (FAILED.equals(existing.getDecisionStatus())) {
                throw new IllegalStateException("Approval callback already failed: "
                        + existing.getErrorMessage());
            }
            throw new IllegalStateException("Approval callback is already being processed");
        }
    }

    public void complete(String approvalId, SupervisorTaskResponse response) {
        AgentWorkflowApprovalClaimEntity entity = new AgentWorkflowApprovalClaimEntity();
        entity.setDecisionStatus(COMPLETED);
        try {
            entity.setResultJson(objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize approval callback result", exception);
        }
        claimMapper.update(entity, new LambdaQueryWrapper<AgentWorkflowApprovalClaimEntity>()
                .eq(AgentWorkflowApprovalClaimEntity::getApprovalId, approvalId)
                .eq(AgentWorkflowApprovalClaimEntity::getDecisionStatus, PROCESSING));
    }

    public void fail(String approvalId, Exception exception) {
        AgentWorkflowApprovalClaimEntity entity = new AgentWorkflowApprovalClaimEntity();
        entity.setDecisionStatus(FAILED);
        entity.setErrorMessage(exception.getMessage());
        claimMapper.update(entity, new LambdaQueryWrapper<AgentWorkflowApprovalClaimEntity>()
                .eq(AgentWorkflowApprovalClaimEntity::getApprovalId, approvalId)
                .eq(AgentWorkflowApprovalClaimEntity::getDecisionStatus, PROCESSING));
    }

    private AgentWorkflowApprovalClaimEntity find(String approvalId) {
        return claimMapper.selectOne(new LambdaQueryWrapper<AgentWorkflowApprovalClaimEntity>()
                .eq(AgentWorkflowApprovalClaimEntity::getApprovalId, approvalId)
                .last("limit 1"));
    }

    public record ClaimResult(boolean acquired, SupervisorTaskResponse replayedResponse) {
        public static ClaimResult claimed() {
            return new ClaimResult(true, null);
        }

        public static ClaimResult replay(SupervisorTaskResponse response) {
            return new ClaimResult(false, response);
        }
    }
}
