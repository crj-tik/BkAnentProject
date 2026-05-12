package com.bkanent.agent.service.impl;

import com.bkanent.agent.config.AgentDeepSeekProperties;
import com.bkanent.agent.model.planner.AgentPlan;
import com.bkanent.agent.model.planner.AgentPlanStep;
import com.bkanent.agent.model.planner.AgentPlannerSession;
import com.bkanent.agent.model.planner.AgentStepExecutionResult;
import com.bkanent.agent.planner.context.AgentPlanStepContext;
import com.bkanent.agent.planner.registry.AgentPlanActionRegistry;
import com.bkanent.agent.service.AgentPlanExecutorService;
import com.bkanent.agent.service.AgentPlannerService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent Planner 执行器实现。
 */
@Service
public class AgentPlanExecutorServiceImpl implements AgentPlanExecutorService {

    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\$\\{([^{}]+)}");

    private final AgentPlannerService agentPlannerService;
    private final AgentDeepSeekProperties agentDeepSeekProperties;
    private final AgentPlanActionRegistry agentPlanActionRegistry;

    public AgentPlanExecutorServiceImpl(AgentPlannerService agentPlannerService,
                                        AgentDeepSeekProperties agentDeepSeekProperties,
                                        AgentPlanActionRegistry agentPlanActionRegistry) {
        this.agentPlannerService = agentPlannerService;
        this.agentDeepSeekProperties = agentDeepSeekProperties;
        this.agentPlanActionRegistry = agentPlanActionRegistry;
    }

    @Override
    public AgentPlannerSession execute(String userMessage) {
        String sessionNo = "PLAN-" + System.currentTimeMillis();
        List<AgentStepExecutionResult> executionResults = new ArrayList<>();
        List<AgentStepExecutionResult> completedResults = new ArrayList<>();
        Map<String, String> outputContext = new LinkedHashMap<>();
        Map<String, Map<String, Object>> payloadContext = new LinkedHashMap<>();
        outputContext.put("userMessage", userMessage);
        AgentStepExecutionResult failedResult = null;
        AgentPlan currentPlan = agentPlannerService.createPlan(userMessage, completedResults, null);
        int replanCount = 0;

        while (true) {
            boolean replanned = false;
            for (AgentPlanStep step : safeSteps(currentPlan)) {
                AgentPlanStepContext stepContext = buildStepContext(step, outputContext, payloadContext);
                if (isDuplicateCompletedStep(stepContext, completedResults)) {
                    executionResults.add(new AgentStepExecutionResult(
                            step.stepNo(),
                            step.action(),
                            true,
                            buildRequest(stepContext),
                            stepContext.resolvedInput(),
                            step.outputKey(),
                            "该步骤已在前一轮执行成功，执行器已跳过。",
                            Map.of("skipped", true, "reason", "该步骤已在前一轮执行成功，执行器已跳过。"),
                            null,
                            true
                    ));
                    continue;
                }
                AgentStepExecutionResult result = agentPlanActionRegistry.execute(stepContext);
                executionResults.add(result);
                if (result.success()) {
                    completedResults.add(result);
                    outputContext.put(step.outputKey(), defaultOutput(result.output()));
                    payloadContext.put(step.outputKey(), defaultPayload(result.outputPayload()));
                    continue;
                }
                failedResult = result;
                if (replanCount >= agentDeepSeekProperties.getPlannerMaxReplanCount()) {
                    return new AgentPlannerSession(sessionNo, currentPlan, executionResults, replanCount, false);
                }
                replanCount++;
                currentPlan = agentPlannerService.createPlan(userMessage, completedResults, failedResult);
                replanned = true;
                break;
            }
            if (!replanned) {
                return new AgentPlannerSession(sessionNo, currentPlan, executionResults, replanCount, true);
            }
        }
    }

    private List<AgentPlanStep> safeSteps(AgentPlan plan) {
        return plan == null || plan.steps() == null ? List.of() : plan.steps();
    }

    private AgentPlanStepContext buildStepContext(AgentPlanStep step,
                                                  Map<String, String> outputContext,
                                                  Map<String, Map<String, Object>> payloadContext) {
        String resolvedInput = resolveInput(step, outputContext);
        Map<String, Object> resolvedArguments = new LinkedHashMap<>();
        if (step.arguments() != null) {
            for (Map.Entry<String, Object> entry : step.arguments().entrySet()) {
                resolvedArguments.put(entry.getKey(), resolveArgumentValue(entry.getValue(), outputContext, payloadContext));
            }
        }
        resolvedArguments.put("_resolvedInput", resolvedInput);
        resolvedArguments.put("_inputKey", step.inputKey());
        resolvedArguments.put("_outputKey", step.outputKey());
        return AgentPlanStepContext.of(step, resolvedArguments, resolvedInput);
    }

    private String resolveInput(AgentPlanStep step, Map<String, String> outputContext) {
        String value = outputContext.get(step.inputKey());
        if (value == null) {
            throw new IllegalStateException("步骤 " + step.stepNo() + " 未找到输入键: " + step.inputKey());
        }
        return value;
    }

    private Object resolveArgumentValue(Object rawValue,
                                        Map<String, String> outputContext,
                                        Map<String, Map<String, Object>> payloadContext) {
        if (rawValue instanceof Map<?, ?> mapValue) {
            Map<String, Object> resolvedMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                resolvedMap.put(String.valueOf(entry.getKey()), resolveArgumentValue(entry.getValue(), outputContext, payloadContext));
            }
            return resolvedMap;
        }
        if (rawValue instanceof Collection<?> collectionValue) {
            List<Object> resolvedList = new ArrayList<>();
            for (Object item : collectionValue) {
                resolvedList.add(resolveArgumentValue(item, outputContext, payloadContext));
            }
            return resolvedList;
        }
        if (!(rawValue instanceof String text)) {
            return rawValue;
        }
        Matcher matcher = TEMPLATE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return rawValue;
        }
        matcher.reset();
        if (matcher.matches() && matcher.groupCount() == 1 && text.equals("${" + matcher.group(1) + "}")) {
            Object resolved = resolveExpression(matcher.group(1), outputContext, payloadContext);
            return resolved == null ? rawValue : resolved;
        }
        StringBuffer builder = new StringBuffer();
        while (matcher.find()) {
            Object resolved = resolveExpression(matcher.group(1), outputContext, payloadContext);
            matcher.appendReplacement(builder, Matcher.quoteReplacement(resolved == null ? matcher.group(0) : String.valueOf(resolved)));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private Object resolveExpression(String expression,
                                     Map<String, String> outputContext,
                                     Map<String, Map<String, Object>> payloadContext) {
        String normalizedExpression = expression == null ? "" : expression.trim();
        if (normalizedExpression.isBlank()) {
            return null;
        }
        if (!normalizedExpression.contains(".")) {
            return outputContext.get(normalizedExpression);
        }
        int dotIndex = normalizedExpression.indexOf('.');
        String outputKey = normalizedExpression.substring(0, dotIndex);
        String fieldPath = normalizedExpression.substring(dotIndex + 1);
        Object resolved = readPayloadPath(payloadContext.get(outputKey), fieldPath);
        if (resolved != null) {
            return resolved;
        }
        String parentOutput = outputContext.get(outputKey);
        if (parentOutput != null && "resultText".equals(fieldPath)) {
            return parentOutput;
        }
        return null;
    }

    private Object readPayloadPath(Object payload, String fieldPath) {
        if (payload == null || fieldPath == null || fieldPath.isBlank()) {
            return null;
        }
        Object current = payload;
        for (String segment : fieldPath.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else if (current instanceof List<?> list && isInteger(segment)) {
                int index = Integer.parseInt(segment);
                if (index < 0 || index >= list.size()) {
                    return null;
                }
                current = list.get(index);
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private boolean isInteger(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            if (!Character.isDigit(text.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private boolean isDuplicateCompletedStep(AgentPlanStepContext context, List<AgentStepExecutionResult> completedResults) {
        String request = buildRequest(context);
        return completedResults.stream()
                .anyMatch(result -> Objects.equals(result.action(), context.step().action()) && Objects.equals(result.request(), request));
    }

    private String buildRequest(AgentPlanStepContext context) {
        return "description=" + context.step().description()
                + ", input=" + context.resolvedInput()
                + ", arguments=" + context.resolvedArguments();
    }

    private String defaultOutput(String output) {
        return output == null ? "" : output;
    }

    private Map<String, Object> defaultPayload(Map<String, Object> payload) {
        return payload == null ? Map.of() : payload;
    }
}
