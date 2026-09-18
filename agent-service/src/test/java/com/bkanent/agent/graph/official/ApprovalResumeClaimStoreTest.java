package com.bkanent.agent.graph.official;

import com.bkanent.agent.entity.AgentWorkflowApprovalClaimEntity;
import com.bkanent.agent.mapper.AgentWorkflowApprovalClaimMapper;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.common.agent.ApprovalCallbackRequest;
import com.bkanent.common.agent.ApprovalStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApprovalResumeClaimStoreTest {

    private final AgentWorkflowApprovalClaimMapper mapper = mock(AgentWorkflowApprovalClaimMapper.class);
    private final ApprovalResumeClaimStore store = new ApprovalResumeClaimStore(mapper, new ObjectMapper());

    @Test
    void firstCallbackClaimsApprovalId() {
        ApprovalResumeClaimStore.ClaimResult result = store.claim(request("approval-1"));

        assertThat(result.acquired()).isTrue();
        assertThat(result.replayedResponse()).isNull();
    }

    @Test
    void duplicateCompletedCallbackReplaysStoredResult() throws Exception {
        SupervisorTaskResponse response = new SupervisorTaskResponse(
                "session-1", "task-1", "COMPLETED", "done", java.util.List.of(),
                "trace-1", "listing-agent", Map.of());
        AgentWorkflowApprovalClaimEntity existing = new AgentWorkflowApprovalClaimEntity();
        existing.setApprovalId("approval-2");
        existing.setDecisionStatus("COMPLETED");
        existing.setResultJson(new ObjectMapper().writeValueAsString(response));
        when(mapper.insert(any(AgentWorkflowApprovalClaimEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(mapper.selectOne(any())).thenReturn(existing);

        ApprovalResumeClaimStore.ClaimResult result = store.claim(request("approval-2"));

        assertThat(result.acquired()).isFalse();
        assertThat(result.replayedResponse()).isEqualTo(response);
    }

    @Test
    void duplicateProcessingCallbackIsRejected() {
        AgentWorkflowApprovalClaimEntity existing = new AgentWorkflowApprovalClaimEntity();
        existing.setApprovalId("approval-3");
        existing.setDecisionStatus("PROCESSING");
        when(mapper.insert(any(AgentWorkflowApprovalClaimEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(mapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> store.claim(request("approval-3")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already being processed");
    }

    private ApprovalCallbackRequest request(String approvalId) {
        return new ApprovalCallbackRequest(approvalId, "task-1", "session-1",
                ApprovalStatus.APPROVED, "reviewer-1", "", "trace-1");
    }
}
