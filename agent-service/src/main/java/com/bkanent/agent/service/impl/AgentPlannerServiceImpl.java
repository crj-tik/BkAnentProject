package com.bkanent.agent.service.impl;

import com.bkanent.agent.config.AgentDeepSeekProperties;
import com.bkanent.agent.model.planner.AgentPlan;
import com.bkanent.agent.model.planner.AgentPlanStep;
import com.bkanent.agent.model.planner.AgentStepExecutionResult;
import com.bkanent.agent.planner.schema.AgentPlannerSchemaService;
import com.bkanent.agent.service.AgentPlannerService;
import com.bkanent.agent.service.DeepSeekChatService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Planner service implementation.
 */
@Service
public class AgentPlannerServiceImpl implements AgentPlannerService {

    private static final String PLANNER_SYSTEM_PROMPT_TEMPLATE = """
            你是房产中台智能体的规划器。
            你必须输出严格串行的 Planner JSON，保证“上一步输出是下一步输入”。
            除第一步外，每一步都必须消费前一步的输出，不允许跳步引用，也不允许并行分叉。

            可用动作及其 JSON Schema：
            %s

            输出必须是 JSON，不要输出 Markdown 代码块。
            输出结构必须严格遵循以下格式：
            {
              "objective": "总体目标",
              "summary": "规划说明",
              "steps": [
                {
                  "stepNo": 1,
                  "action": "SEARCH_KNOWLEDGE",
                  "description": "为什么执行这一步",
                  "inputFromStepNo": null,
                  "inputKey": "userMessage",
                  "outputKey": "knowledgeResult",
                  "arguments": {
                    "query": "从用户问题提取的检索词"
                  }
                },
                {
                  "stepNo": 2,
                  "action": "FINAL_RESPONSE",
                  "description": "基于上一步结果组织最终答复",
                  "inputFromStepNo": 1,
                  "inputKey": "knowledgeResult",
                  "outputKey": "finalAnswerDraft",
                  "arguments": {
                    "summary": "请结合 ${knowledgeResult.resultText} 输出结论"
                  }
                }
              ]
            }

            规则如下：
            1. stepNo 必须从 1 开始连续递增。
            2. 第一步 inputFromStepNo 必须为 null，inputKey 必须为 userMessage。
            3. 从第二步开始，inputFromStepNo 必须严格等于前一步的 stepNo。
            4. 从第二步开始，inputKey 必须严格等于前一步的 outputKey。
            5. 每一步都必须设置 outputKey，且不能为空。
            6. arguments 必须满足对应 action 的必填参数要求。
            7. 如需引用上一步 outputPayload 中的字段，可写成 ${outputKey.fieldPath}，例如 ${kpiResult.month}。
            8. 如需拼接文本和多个上游字段，可使用模板字符串。
            9. 如果已有完成步骤，不要重复规划已完成步骤，只规划剩余步骤。
            10. 如果上一步失败，请调整动作或参数，避免重复失败方案。
            """;

    private static final String PLANNER_FIX_SYSTEM_PROMPT = """
            你是房产中台智能体的 Planner 修正器。
            你的任务是修复一个不合法的 Planner JSON。
            你只能返回修正后的 JSON，不能输出解释，也不能输出 Markdown。
            修复时必须保持原始目标不变，并严格满足 Planner 协议约束。
            """;

    private static final String FINAL_ANSWER_SYSTEM_PROMPT = """
            你是房产中台智能体的总结器。
            请基于执行器产出的真实结果组织最终答复。
            回答必须忠于执行结果，不允许编造。
            如果执行未完全成功，需要明确说明失败点和下一步建议。
            """;

    private final DeepSeekChatService deepSeekChatService;
    private final ObjectMapper objectMapper;
    private final AgentPlannerSchemaService agentPlannerSchemaService;
    private final AgentDeepSeekProperties agentDeepSeekProperties;

    public AgentPlannerServiceImpl(DeepSeekChatService deepSeekChatService,
                                   ObjectMapper objectMapper,
                                   AgentPlannerSchemaService agentPlannerSchemaService,
                                   AgentDeepSeekProperties agentDeepSeekProperties) {
        this.deepSeekChatService = deepSeekChatService;
        this.objectMapper = objectMapper;
        this.agentPlannerSchemaService = agentPlannerSchemaService;
        this.agentDeepSeekProperties = agentDeepSeekProperties;
    }

    @Override
    public AgentPlan createPlan(String userMessage, List<AgentStepExecutionResult> completedResults, AgentStepExecutionResult failedResult) {
        String response = deepSeekChatService.call(buildPlannerSystemPrompt(), buildPlannerPrompt(userMessage, completedResults, failedResult));
        return parsePlanWithAutoFix(response, 0);
    }

    @Override
    public String buildFinalAnswer(String userMessage, List<AgentStepExecutionResult> executionResults, boolean completed) {
        return deepSeekChatService.call(
                FINAL_ANSWER_SYSTEM_PROMPT,
                """
                        用户原始问题：
                        %s

                        执行结果：
                        %s

                        当前执行状态：%s
                        """.formatted(
                        userMessage,
                        formatExecutionResults(executionResults),
                        completed ? "已完成" : "未完成"
                )
        );
    }

    private String buildPlannerSystemPrompt() {
        return PLANNER_SYSTEM_PROMPT_TEMPLATE.formatted(agentPlannerSchemaService.buildActionSchemaPrompt());
    }

    private String buildPlannerPrompt(String userMessage,
                                      List<AgentStepExecutionResult> completedResults,
                                      AgentStepExecutionResult failedResult) {
        return """
                用户原始问题：
                %s

                已完成步骤：
                %s

                最近失败步骤：
                %s
                """.formatted(
                userMessage,
                completedResults == null || completedResults.isEmpty() ? "无" : formatExecutionResults(completedResults),
                failedResult == null ? "无" : formatExecutionResults(List.of(failedResult))
        );
    }

    private String formatExecutionResults(List<AgentStepExecutionResult> executionResults) {
        StringBuilder builder = new StringBuilder();
        for (AgentStepExecutionResult result : executionResults) {
            builder.append("步骤=")
                    .append(result.stepNo())
                    .append("，动作=")
                    .append(result.action())
                    .append("，成功=")
                    .append(result.success())
                    .append("，输入=")
                    .append(result.resolvedInput())
                    .append("，输出键=")
                    .append(result.outputKey())
                    .append("，输出=")
                    .append(result.output())
                    .append("，错误=")
                    .append(result.errorMessage())
                    .append(System.lineSeparator());
        }
        return builder.toString().trim();
    }

    private AgentPlan parsePlanWithAutoFix(String response, int retryCount) {
        try {
            return parsePlan(response);
        } catch (IllegalStateException exception) {
            if (retryCount >= agentDeepSeekProperties.getPlannerFixRetryCount()) {
                throw exception;
            }
            String fixedResponse = deepSeekChatService.call(
                    PLANNER_FIX_SYSTEM_PROMPT,
                    """
                            当前 Planner JSON 非法，请修复。
                            非法原因：
                            %s

                            原始 Planner JSON：
                            %s

                            动作定义：
                            %s
                            """.formatted(
                            exception.getMessage(),
                            response,
                            agentPlannerSchemaService.buildActionSchemaPrompt()
                    )
            );
            return parsePlanWithAutoFix(fixedResponse, retryCount + 1);
        }
    }

    private AgentPlan parsePlan(String response) {
        try {
            String json = cleanupJson(response);
            JsonNode root = objectMapper.readTree(json);
            String objective = root.path("objective").asText("完成用户请求");
            String summary = root.path("summary").asText("模型未提供规划说明");
            List<AgentPlanStep> steps = new ArrayList<>();
            JsonNode stepsNode = root.path("steps");
            if (stepsNode.isArray()) {
                for (JsonNode stepNode : stepsNode) {
                    Map<String, Object> arguments = objectMapper.convertValue(
                            stepNode.path("arguments"),
                            new TypeReference<Map<String, Object>>() {
                            }
                    );
                    steps.add(new AgentPlanStep(
                            stepNode.path("stepNo").asInt(steps.size() + 1),
                            stepNode.path("action").asText(),
                            stepNode.path("description").asText(),
                            stepNode.path("inputFromStepNo").isNull() ? null : stepNode.path("inputFromStepNo").asInt(),
                            stepNode.path("inputKey").asText(),
                            stepNode.path("outputKey").asText(),
                            arguments
                    ));
                }
            }
            return new AgentPlan(objective, summary, normalizeAndValidateSteps(steps));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Planner result is not valid JSON: " + response, exception);
        }
    }

    private List<AgentPlanStep> normalizeAndValidateSteps(List<AgentPlanStep> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalStateException("Planner must contain at least one step");
        }
        List<AgentPlanStep> normalizedSteps = new ArrayList<>();
        for (int index = 0; index < steps.size(); index++) {
            AgentPlanStep sourceStep = steps.get(index);
            int expectedStepNo = index + 1;
            Integer inputFromStepNo = index == 0 ? null : expectedStepNo - 1;
            String inputKey = index == 0
                    ? defaultIfBlank(sourceStep.inputKey(), "userMessage")
                    : defaultIfBlank(sourceStep.inputKey(), normalizedSteps.get(index - 1).outputKey());
            String outputKey = defaultIfBlank(sourceStep.outputKey(), "step" + expectedStepNo + "Result");
            AgentPlanStep normalizedStep = new AgentPlanStep(
                    expectedStepNo,
                    sourceStep.action(),
                    sourceStep.description(),
                    inputFromStepNo,
                    inputKey,
                    outputKey,
                    sourceStep.arguments() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(sourceStep.arguments())
            );
            validateChainRule(normalizedStep, normalizedSteps);
            normalizedSteps.add(normalizedStep);
        }
        return normalizedSteps;
    }

    private void validateChainRule(AgentPlanStep step, List<AgentPlanStep> normalizedSteps) {
        if (step.action() == null || step.action().isBlank()) {
            throw new IllegalStateException("Step " + step.stepNo() + " is missing action");
        }
        if (step.outputKey() == null || step.outputKey().isBlank()) {
            throw new IllegalStateException("Step " + step.stepNo() + " is missing outputKey");
        }
        if (step.stepNo() == 1) {
            if (step.inputFromStepNo() != null) {
                throw new IllegalStateException("The first step must have inputFromStepNo = null");
            }
            if (!"userMessage".equals(step.inputKey())) {
                throw new IllegalStateException("The first step must use inputKey = userMessage");
            }
            return;
        }
        AgentPlanStep previousStep = normalizedSteps.get(normalizedSteps.size() - 1);
        if (!Integer.valueOf(previousStep.stepNo()).equals(step.inputFromStepNo())) {
            throw new IllegalStateException("Step " + step.stepNo() + " must reference the previous step number");
        }
        if (!previousStep.outputKey().equals(step.inputKey())) {
            throw new IllegalStateException("Step " + step.stepNo() + " must consume the previous step outputKey");
        }
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String cleanupJson(String response) {
        if (response == null) {
            return "{}";
        }
        String cleaned = response.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```json", "").replaceFirst("^```", "").replaceFirst("```$", "").trim();
        }
        return cleaned;
    }
}
