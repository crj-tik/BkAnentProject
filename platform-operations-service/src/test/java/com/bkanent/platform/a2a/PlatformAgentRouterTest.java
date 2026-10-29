package com.bkanent.platform.a2a;

import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PlatformAgentRouterTest {
    @Test
    void exactAuthorizedCapabilitySelectsOnlyItsBranch() throws Exception {
        var analytics = mock(AgentExecutor.class);
        var review = mock(AgentExecutor.class);
        var knowledge = mock(AgentExecutor.class);
        var operations = new ArrayList<String>();
        var router = new PlatformAgentRouter(analytics, review, knowledge, operations::add);
        var events = mock(EventQueue.class);
        var request = request("knowledge", "task-1");
        router.execute(request, events);
        assertThat(operations).containsExactly("agent.workflow.invoke.platform-operations.knowledge");
        verify(knowledge).execute(request, events);
        verifyNoInteractions(analytics, review);
    }

    @Test
    void invalidOrUnauthorizedCapabilityNeverReachesAnyBranch() {
        var analytics = mock(AgentExecutor.class);
        var review = mock(AgentExecutor.class);
        var knowledge = mock(AgentExecutor.class);
        var router = new PlatformAgentRouter(analytics, review, knowledge,
                operation -> { throw new RagException(RagErrorCode.FORBIDDEN); });
        var events = mock(EventQueue.class);
        assertThatThrownBy(() -> router.execute(request("unknown", "task-1"), events))
                .isInstanceOf(RagException.class);
        assertThatThrownBy(() -> router.execute(request("review", "task-2"), events))
                .isInstanceOf(RagException.class)
                .extracting(failure -> ((RagException) failure).code()).isEqualTo(RagErrorCode.FORBIDDEN);
        verifyNoInteractions(analytics, review, knowledge);
    }

    @Test
    void cancellationUsesTheActiveTaskBranch() throws Exception {
        var analytics = mock(AgentExecutor.class);
        var review = mock(AgentExecutor.class);
        var knowledge = mock(AgentExecutor.class);
        var router = new PlatformAgentRouter(analytics, review, knowledge, operation -> {});
        var events = mock(EventQueue.class);
        var request = request("review", "task-3");
        doAnswer(invocation -> { router.cancel(request, events); return null; }).when(review).execute(request, events);
        router.execute(request, events);
        verify(review).cancel(request, events);
        verifyNoInteractions(analytics, knowledge);
    }

    private static RequestContext request(String capability, String taskId) {
        var message = new Message.Builder().role(Message.Role.USER).parts(List.of(new TextPart("test")))
                .taskId(taskId).contextId("context-1").build();
        var params = new MessageSendParams(message, null, Map.of(PlatformAgentRouter.METADATA_KEY, capability));
        return new RequestContext.Builder().setParams(params).setTaskId(taskId).setContextId("context-1").build();
    }
}
