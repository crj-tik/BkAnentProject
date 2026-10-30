package com.bkanent.businessreview.a2a;

import com.bkanent.businessreview.tool.ReviewDraftTools;
import com.bkanent.common.a2a.A2aCredentialContext;
import com.bkanent.common.a2a.A2aMemoryAgentHook;
import com.bkanent.common.a2a.A2aMemoryHookFactory;
import com.bkanent.common.a2a.A2aMemoryProperties;
import com.bkanent.common.a2a.A2aExecutionProperties;
import com.bkanent.common.memory.MemoryV2RpcService;
import com.bkanent.common.memory.MemoryException;
import com.bkanent.common.memory.MemoryErrorCode;
import com.bkanent.common.rag.A2aBeaconBinding;
import com.bkanent.common.rag.RagCredentialContext;
import com.bkanent.common.skill.core.SkillFileLoader;
import com.bkanent.common.skill.core.SkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendConfiguration;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import com.bkanent.common.a2a.OfficialA2aAgentExecutor;
import com.bkanent.memory.runtime.MemoryWorkingSetCache;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessReviewMemoryWiringTest {
    @Test
    void serviceUsesOnlyItsMemoryIdentityAndFailsBeforeModelWhenMemoryIsUnavailableOrDenied() throws Exception {
        assertMemoryFailureStopsBeforeModel(new IllegalStateException("memory unavailable"));
        assertMemoryFailureStopsBeforeModel(new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED));
        assertMemoryFailureStopsBeforeModel(new MemoryException(MemoryErrorCode.FORBIDDEN));
    }

    private void assertMemoryFailureStopsBeforeModel(RuntimeException memoryFailure) throws Exception {
        MemoryV2RpcService memory = mock(MemoryV2RpcService.class);
        when(memory.manageWriter(any(), any())).thenThrow(memoryFailure);
        A2aMemoryProperties memoryProperties = new A2aMemoryProperties();
        memoryProperties.setKeyId("business-review-test-current");
        memoryProperties.setPrivateKeyFile(privateKeyFile());
        A2aMemoryHookFactory factory = new A2aMemoryHookFactory(memoryProperties, () -> memory,
                new MemoryWorkingSetCache(8, 1024 * 1024, 60_000, Clock.systemUTC()), new ObjectMapper());
        ChatModel model = mock(ChatModel.class);
        SkillRegistry skills = new SkillRegistry(new SkillFileLoader(), "");
        BusinessReviewOfficialA2aAgent component = new BusinessReviewOfficialA2aAgent(model,
                new BusinessReviewAgentProperties(), new ReviewDraftTools(), skills, factory);

        A2aMemoryAgentHook hook = (A2aMemoryAgentHook) ReflectionTestUtils.getField(component, "memoryHook");
        assertThat(ReflectionTestUtils.getField(hook, "caller")).isEqualTo("platform-operations-service");
        assertThat(ReflectionTestUtils.getField(hook, "domain")).isEqualTo("business-review");
        assertThat(ReflectionTestUtils.getField(hook, "agentId")).isEqualTo("platform-operations-review");
        AgentExecutor executor = component.businessReviewA2aAgentExecutor(new ObjectMapper(), new A2aExecutionProperties());
        assertThat(executor).isInstanceOf(OfficialA2aAgentExecutor.class);
        assertThat(ReflectionTestUtils.getField(executor, "memoryHook")).isSameAs(hook);

        String taskId = "business-review-memory-" + Integer.toHexString(System.identityHashCode(memoryFailure));
        RequestContext context = requestContext(taskId);
        long expiresAt = Instant.now().getEpochSecond() + 60;
        A2aBeaconBinding binding = new A2aBeaconBinding(1, "request-" + taskId, "agent-service",
                "platform-operations-service", "17", "message/send", "0".repeat(64), taskId, expiresAt);
        var lease = new A2aCredentialContext.Lease(binding, RagCredentialContext.beacon("memory-wiring-test"),
                expiresAt, () -> { });
        EventQueue queue = EventQueue.create();
        try (var ignored = A2aCredentialContext.open(lease)) {
            executor.execute(context, queue);
        } finally {
            queue.close();
            factory.close();
        }

        verify(memory).manageWriter(any(), any());
        verify(memory, never()).readWorkingSet(any(), any());
        verify(model, never()).call(any(Prompt.class));
    }

    private static RequestContext requestContext(String taskId) {
        Message message = new Message.Builder().role(Message.Role.USER).parts(List.of(new TextPart("读取门店复盘上下文")))
                .taskId(taskId).contextId(taskId).build();
        MessageSendParams params = new MessageSendParams(message,
                new MessageSendConfiguration(List.of("text"), null, null, false), Map.of());
        return new RequestContext.Builder().setParams(params).setTaskId(taskId).setContextId(taskId).build();
    }

    private static String privateKeyFile() throws Exception {
        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyPair.getPrivate().getEncoded());
        Path path = Files.createTempFile("business-review-memory-test-", ".pem");
        Files.writeString(path, "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n");
        path.toFile().deleteOnExit();
        return path.toString();
    }
}
