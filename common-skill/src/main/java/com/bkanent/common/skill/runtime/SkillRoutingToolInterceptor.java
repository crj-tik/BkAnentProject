package com.bkanent.common.skill.runtime;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.bkanent.common.agent.SkillSelection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** Actual execution boundary, independent of which tools the model was shown. */
public final class SkillRoutingToolInterceptor extends ToolInterceptor {
    private static final Logger log = LoggerFactory.getLogger(SkillRoutingToolInterceptor.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override public String getName() { return "skill-execution-scope"; }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        SkillExecutionContext execution = SkillExecutionContext.from(request.getContext());
        if (execution == null && request.getExecutionContext().isPresent()) {
            execution = SkillExecutionContext.from(request.getExecutionContext().get().config().metadata().orElse(Map.of()));
        }
        if (execution == null) return handler.call(request);
        if (!SkillTool.TOOL_NAME.equals(request.getToolName())) {
            try {
                execution.assertToolAllowed(request.getToolName());
            } catch (SkillExecutionException exception) {
                return ToolCallResponse.error(request.getToolCallId(), request.getToolName(), exception.code() + ": " + exception.getMessage());
            }
            return handler.call(request);
        }
        try {
            Map<?, ?> arguments = mapper.readValue(request.getArguments(), Map.class);
            String task = text(arguments.get("task"));
            if (task == null || task.isBlank()) {
                return ToolCallResponse.error(request.getToolCallId(), request.getToolName(), "TOOL_ARGUMENTS_INVALID: task required");
            }
            var snapshot = execution.activate(new SkillSelection(text(arguments.get("name")),
                    text(arguments.get("version")), null, null));
            String result = "技能 " + snapshot.definition().name() + " 已激活。\n[执行指引]\n"
                    + snapshot.definition().systemPrompt() + "\n[原始请求]\n" + execution.originalTask()
                    + "\n[本次技能任务]\n" + task + "\n[可用工具]\n" + execution.availableCapabilities().values();
            var response = ToolCallResponse.of(request.getToolCallId(), request.getToolName(), result);
            // KI-46: correlate the exact task without exposing confidential text or argument/body payloads.
            log.info("Skill '{}' activated (task=sha256:{}, taskChars={}, taskSummary=redacted, callId={}, threadId={})",
                    SkillLogFingerprints.safeField(snapshot.definition().name()),
                    SkillLogFingerprints.taskFingerprint(task), task.length(),
                    SkillLogFingerprints.safeField(request.getToolCallId()), SkillLogFingerprints.safeField(request.getExecutionContext()
                            .flatMap(context -> context.threadId()).orElse(null)));
            return response;
        } catch (SkillExecutionException exception) {
            return ToolCallResponse.error(request.getToolCallId(), request.getToolName(), exception.code() + ": " + exception.getMessage());
        } catch (Exception exception) {
            return ToolCallResponse.error(request.getToolCallId(), request.getToolName(), "TOOL_ARGUMENTS_INVALID: invalid skill request");
        }
    }

    private String text(Object value) { return value instanceof String text ? text : null; }
}
