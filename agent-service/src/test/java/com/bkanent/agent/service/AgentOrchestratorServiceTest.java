package com.bkanent.agent.service;

import com.bkanent.agent.config.AgentChatProperties;
import com.bkanent.agent.model.chat.*;
import com.bkanent.agent.model.distributed.*;
import com.bkanent.agent.orchestration.SupervisorToolLoopRunner;
import com.bkanent.common.agent.SkillSelection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentOrchestratorServiceTest {
    @Test
    void chatUsesSameCoreAndPreservesExistingFieldsAndContinuationPolicy() throws Exception {
        var runner = mock(SupervisorToolLoopRunner.class);
        var mapper = new ObjectMapper();
        var service = new AgentOrchestratorService(runner, new AgentChatProperties(), mapper);
        when(runner.execute(any())).thenReturn(new SupervisorTaskResponse("session", "run", "WAITING_USER_INPUT", "地区?",
                List.of(), "trace", null, Map.of("mode", "EXPLICIT_SKILL", "calls", List.of())));
        var selected = new SkillSelection("find", "1");
        var response = service.chat(new AgentChatRequest("1", "find", "knowledge", 3, false, selected, null, "session", "run"));
        var capture = org.mockito.ArgumentCaptor.forClass(SupervisorTaskRequest.class);
        verify(runner).execute(capture.capture());
        assertThat(capture.getValue().skill()).isEqualTo(selected);
        assertThat(capture.getValue().allowMcp()).isFalse();
        assertThat(capture.getValue().context()).containsEntry("collectionName", "knowledge").containsEntry("topK", 3);
        assertThat(mapper.readTree(mapper.writeValueAsString(response)).fieldNames()).toIterable()
                .contains("answer", "model", "decision", "toolResults", "toolContext", "runId", "status");
        assertThat(response.status()).isEqualTo("WAITING_USER_INPUT");
        clearInvocations(runner);
        service.chat(new AgentChatRequest("1", "浦东", null, null, null, null, "run", null, "input"));
        verify(runner).execute(capture.capture());
        assertThat(capture.getValue().context()).isEmpty();
        assertThat(capture.getValue().continueRunId()).isEqualTo("run");
    }
}
