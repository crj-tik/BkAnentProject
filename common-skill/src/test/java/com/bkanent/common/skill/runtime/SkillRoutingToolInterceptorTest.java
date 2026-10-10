package com.bkanent.common.skill.runtime;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallExecutionContext;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.bkanent.common.agent.SkillSelection;
import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.core.SkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillRoutingToolInterceptorTest {
    private final SkillRegistry registry = mock(SkillRegistry.class);
    private final SkillRoutingToolInterceptor interceptor = new SkillRoutingToolInterceptor();
    private final Map<String, String> tools = Map.of("local:search", "search", "local:write", "write");
    private final Logger logger = (Logger) LoggerFactory.getLogger(SkillRoutingToolInterceptor.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureActivationLogs() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender.start();
        logger.addAppender(appender);
        when(registry.getByName("find")).thenReturn(skill("find", false));
    }

    @AfterEach
    void restoreLogger() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    @Test
    void successfulActivationLogsLoadedNameAndExactTaskFingerprintWithCallAndThreadCorrelation() throws Exception {
        var execution = execution(null);
        String task = "按预算找房";
        var response = activate(execution, "find", task, "call-1", "a2a-task-1");

        assertThat(response.isError()).isFalse();
        assertThat(execution.snapshot().definition().name()).isEqualTo("find");
        assertThat(execution.availableCapabilities()).containsOnlyKeys("local:search");
        assertThat(response.getResult()).contains("先理解预算再检索", "原始保密需求", task);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(appender.list.get(0).getFormattedMessage()).isEqualTo(
                "Skill 'find' activated (task=sha256:8632bb20f748ea9951a034e55eda910a77b668630fa8fe40833d266c803f1b9a"
                        + ", taskChars=5, taskSummary=redacted, callId=call-1, threadId=a2a-task-1)");
    }

    @Test
    void successfulActivationViaRunnableMetadataAlsoLogsCorrelation() throws Exception {
        var execution = execution(null);
        var config = RunnableConfig.builder().threadId("a2a-task-2")
                .addMetadata(SkillExecutionContext.KEY, execution).build();
        var request = new ToolCallRequest("skill", arguments("find", "按预算找房"), "call-2", Map.of(),
                new ToolCallExecutionContext(config, new OverAllState()));

        assertThat(interceptor.interceptToolCall(request, ignored -> { throw new AssertionError(); }).isError()).isFalse();
        assertThat(execution.snapshot()).isNotNull();
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("callId=call-2, threadId=a2a-task-2");
    }

    @Test
    void unsuccessfulActivationsNeverLogSuccessOrActivate() throws Exception {
        when(registry.getByName("explicit")).thenReturn(skill("explicit", true));
        var execution = execution(null);
        for (String arguments : List.of("not-json", "null", "[]", "{}", "{\"name\":\"find\",\"task\":42}",
                arguments("find", "\r\n"), arguments("missing", "找房"), arguments("explicit", "找房"),
                "{\"name\":\"find\",\"version\":\"2\",\"task\":\"找房\"}")) {
            var request = new ToolCallRequest("skill", arguments, "rejected", Map.of(SkillExecutionContext.KEY, execution));
            assertThat(interceptor.interceptToolCall(request, ignored -> { throw new AssertionError(); }).isError()).isTrue();
            assertThat(execution.snapshot()).isNull();
        }
        assertThat(appender.list).isEmpty();
    }

    @Test
    void rejectedSwitchPreservesSuccessfulSnapshotWithoutAnotherSuccessLog() throws Exception {
        var execution = execution(null);
        assertThat(activate(execution, "find", "找房", "accepted", null).isError()).isFalse();
        var snapshot = execution.snapshot();
        appender.list.clear();

        assertThat(activate(execution, "missing", "找房", "rejected", null).isError()).isTrue();
        assertThat(execution.snapshot()).isSameAs(snapshot);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void explicitSelectionRemainsPinnedAndRejectedSwitchDoesNotLogSuccess() throws Exception {
        var execution = execution(new SkillSelection("find", "1"));
        var snapshot = execution.snapshot();
        when(registry.getByName("find")).thenReturn(skill("find", true));

        assertThat(activate(execution, "find", "找房", "accepted", null).isError()).isFalse();
        assertThat(execution.snapshot()).isSameAs(snapshot);
        assertThat(appender.list).hasSize(1);
        appender.list.clear();
        assertThat(activate(execution, "missing", "找房", "rejected", null).isError()).isTrue();
        assertThat(execution.snapshot()).isSameAs(snapshot);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void plainDefaultToolForwardsWithoutActivationOrSuccessLog() {
        var execution = execution(null);
        var request = new ToolCallRequest("search", "{\"secret\":\"not-for-logs\"}", "plain-call",
                Map.of(SkillExecutionContext.KEY, execution));
        var expected = ToolCallResponse.of("plain-call", "search", "result");
        AtomicInteger calls = new AtomicInteger();

        assertThat(interceptor.interceptToolCall(request, forwarded -> {
            assertThat(forwarded).isSameAs(request);
            calls.incrementAndGet();
            return expected;
        })).isSameAs(expected);
        assertThat(calls).hasValue(1);
        assertThat(execution.snapshot()).isNull();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void unmanagedPathStillForwardsWithoutManagedSuccessLog() {
        var request = new ToolCallRequest("skill", "{}", "legacy-call", Map.of());
        var expected = ToolCallResponse.of("legacy-call", "skill", "legacy response");
        assertThat(interceptor.interceptToolCall(request, ignored -> expected)).isSameAs(expected);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void logsAreBoundedSingleLineAndDoNotExposeTaskBodyOriginalRequestOrExtraArguments() throws Exception {
        String name = "find\r\n' forged=" + "n".repeat(200);
        when(registry.getByName(name)).thenReturn(skill(name, false));
        var execution = execution(null);
        String task = "\r\nFAKE SUCCESS\u2028\u2029\u0000 token=sk-private-secret email=person@example.com phone=13800138000 "
                + "机密合同条款".repeat(2_000);
        String callId = "call\r\n' forged=" + "c".repeat(200);
        String threadId = "thread\u2028 forged=" + "t".repeat(200);

        assertThat(activate(execution, name, task, callId, threadId).isError()).isFalse();
        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(task.getBytes(StandardCharsets.UTF_8)));
        assertThat(message).contains("task=sha256:" + fingerprint, "taskChars=" + task.length(), "taskSummary=redacted")
                .doesNotContain("\r", "\n", "\u2028", "\u2029", "\u0000", "FAKE SUCCESS", "sk-private-secret",
                        "person@example.com", "13800138000", "机密合同条款", "先理解预算再检索", "原始保密需求", "extra-argument-secret");
        assertThat(message).matches("Skill '[A-Za-z0-9._-]{1,96}' activated \\(task=sha256:[0-9a-f]{64}, "
                + "taskChars=[0-9]+, taskSummary=redacted, callId=[A-Za-z0-9._-]{1,96}, threadId=[A-Za-z0-9._-]{1,96}\\)");
        assertThat(message.length()).isLessThan(512);
        assertThat(activate(execution, name, task + "!", "another-call", null).isError()).isFalse();
        assertThat(appender.list.get(1).getFormattedMessage()).doesNotContain("task=sha256:" + fingerprint)
                .contains("threadId=unknown");
    }

    private SkillExecutionContext execution(SkillSelection selection) {
        return new SkillExecutionContext(registry, "listing", "原始保密需求", tools, selection);
    }

    private ToolCallResponse activate(SkillExecutionContext execution, String name, String task,
                                      String callId, String threadId) throws Exception {
        var config = RunnableConfig.builder().threadId(threadId).build();
        var request = new ToolCallRequest("skill", arguments(name, task), callId,
                Map.of(SkillExecutionContext.KEY, execution), new ToolCallExecutionContext(config, new OverAllState()));
        return interceptor.interceptToolCall(request, ignored -> { throw new AssertionError(); });
    }

    private String arguments(String name, String task) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of("name", name, "task", task,
                "context", Map.of("secret", "extra-argument-secret")));
    }

    private SkillDefinition skill(String name, boolean explicitOnly) {
        return SkillDefinition.builder().name(name).description("find").domain("listing").tools(List.of("search"))
                .systemPrompt("先理解预算再检索").explicitOnly(explicitOnly).build();
    }
}
