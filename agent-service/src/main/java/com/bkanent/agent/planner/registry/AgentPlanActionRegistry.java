package com.bkanent.agent.planner.registry;

import com.bkanent.agent.mcp.AgentMcpClient;
import com.bkanent.agent.mcp.AgentToolExecutionType;
import com.bkanent.agent.mcp.model.AgentMcpCallResult;
import com.bkanent.agent.model.planner.AgentStepExecutionResult;
import com.bkanent.agent.planner.annotation.AgentPlannerAction;
import com.bkanent.agent.planner.annotation.AgentPlannerParam;
import com.bkanent.agent.planner.context.AgentPlanStepContext;
import com.bkanent.agent.planner.definition.AgentActionExecutionOutput;
import com.bkanent.agent.planner.definition.AgentPlanActionDefinition;
import com.bkanent.agent.planner.definition.AgentPlanActionMethodDefinition;
import com.bkanent.agent.planner.schema.AgentPlannerSchemaValidator;
import com.bkanent.agent.tool.AgentPlannerTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Planner action registry supporting both local bean execution and MCP execution.
 */
@Component
public class AgentPlanActionRegistry {

    private final Map<String, AgentPlanActionMethodDefinition> definitionMap;
    private final AgentPlannerSchemaValidator agentPlannerSchemaValidator;
    private final AgentMcpClient agentMcpClient;

    public AgentPlanActionRegistry(List<AgentPlannerTool> toolBeans,
                                   AgentPlannerSchemaValidator agentPlannerSchemaValidator,
                                   AgentMcpClient agentMcpClient) {
        this.definitionMap = new LinkedHashMap<>();
        this.agentPlannerSchemaValidator = agentPlannerSchemaValidator;
        this.agentMcpClient = agentMcpClient;
        for (Object toolBean : toolBeans) {
            registerToolBean(toolBean);
        }
    }

    public AgentPlanActionDefinition getRequiredDefinition(String action) {
        AgentPlanActionMethodDefinition methodDefinition = definitionMap.get(action);
        if (methodDefinition == null) {
            throw new IllegalArgumentException("Planner action not found: " + action);
        }
        return methodDefinition.definition();
    }

    public List<AgentPlanActionDefinition> listDefinitions() {
        return definitionMap.values().stream().map(AgentPlanActionMethodDefinition::definition).toList();
    }

    public AgentStepExecutionResult execute(AgentPlanStepContext context) {
        AgentPlanActionMethodDefinition methodDefinition = getRequiredMethodDefinition(context.step().action());
        String request = buildRequest(context);
        try {
            validateRequiredArguments(methodDefinition.definition(), context.resolvedArguments());
            agentPlannerSchemaValidator.validateInput(
                    context.step().action(),
                    methodDefinition.definition().inputSchema(),
                    context.resolvedArguments()
            );
            AgentActionExecutionOutput output = methodDefinition.definition().executionType() == AgentToolExecutionType.MCP_CLIENT
                    ? executeByMcp(methodDefinition.definition(), context.resolvedArguments())
                    : executeByLocalMethod(methodDefinition, context.resolvedArguments());
            agentPlannerSchemaValidator.validateOutput(
                    context.step().action(),
                    methodDefinition.definition().outputSchema(),
                    output.payload()
            );
            return new AgentStepExecutionResult(
                    context.step().stepNo(),
                    context.step().action(),
                    true,
                    request,
                    context.resolvedInput(),
                    context.step().outputKey(),
                    output.text(),
                    output.payload(),
                    null,
                    false
            );
        } catch (InvocationTargetException exception) {
            Throwable targetException = exception.getTargetException();
            return fail(context, request, targetException == null ? exception.getMessage() : targetException.getMessage());
        } catch (Exception exception) {
            return fail(context, request, exception.getMessage());
        }
    }

    private AgentActionExecutionOutput executeByLocalMethod(AgentPlanActionMethodDefinition methodDefinition,
                                                            Map<String, Object> arguments) throws InvocationTargetException, IllegalAccessException {
        Object[] args = buildMethodArguments(methodDefinition, arguments);
        Object rawResult = methodDefinition.method().invoke(methodDefinition.bean(), args);
        return toExecutionOutput(rawResult, arguments);
    }

    private AgentActionExecutionOutput executeByMcp(AgentPlanActionDefinition definition,
                                                    Map<String, Object> arguments) {
        AgentMcpCallResult result = agentMcpClient.callTool(
                definition.mcpServerName(),
                definition.mcpToolName(),
                arguments
        );
        return AgentActionExecutionOutput.of(
                result.text(),
                result.payload() == null ? Map.of() : result.payload()
        );
    }

    private void registerToolBean(Object toolBean) {
        Class<?> targetClass = AopUtils.getTargetClass(toolBean);
        if (targetClass == null) {
            targetClass = toolBean.getClass();
        }
        for (Method method : targetClass.getDeclaredMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            AgentPlannerAction plannerAction = method.getAnnotation(AgentPlannerAction.class);
            if (tool == null || plannerAction == null) {
                continue;
            }
            List<String> argumentNames = resolveArgumentNames(method);
            AgentPlanActionDefinition definition = new AgentPlanActionDefinition(
                    plannerAction.action(),
                    tool.description(),
                    List.of(plannerAction.requiredArguments()),
                    plannerAction.inputDescription(),
                    plannerAction.outputDescription(),
                    plannerAction.exampleArguments(),
                    plannerAction.inputSchema(),
                    plannerAction.outputSchema(),
                    plannerAction.exampleOutput(),
                    plannerAction.executionType(),
                    plannerAction.mcpServerName(),
                    plannerAction.mcpToolName()
            );
            method.setAccessible(true);
            definitionMap.put(plannerAction.action(), new AgentPlanActionMethodDefinition(definition, toolBean, method, argumentNames));
        }
    }

    private AgentPlanActionMethodDefinition getRequiredMethodDefinition(String action) {
        AgentPlanActionMethodDefinition methodDefinition = definitionMap.get(action);
        if (methodDefinition == null) {
            throw new IllegalArgumentException("Planner action binding not found: " + action);
        }
        return methodDefinition;
    }

    private List<String> resolveArgumentNames(Method method) {
        return List.of(method.getParameters()).stream().map(this::resolveArgumentName).toList();
    }

    private String resolveArgumentName(Parameter parameter) {
        AgentPlannerParam plannerParam = parameter.getAnnotation(AgentPlannerParam.class);
        if (plannerParam == null) {
            throw new IllegalStateException("Tool parameter missing @AgentPlannerParam: " + parameter);
        }
        return plannerParam.value();
    }

    private void validateRequiredArguments(AgentPlanActionDefinition definition, Map<String, Object> arguments) {
        if (definition.requiredArguments() == null || definition.requiredArguments().isEmpty()) {
            return;
        }
        for (String requiredArgument : definition.requiredArguments()) {
            Object value = arguments.get(requiredArgument);
            if (value == null || String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException("Missing required argument: " + requiredArgument);
            }
        }
    }

    private Object[] buildMethodArguments(AgentPlanActionMethodDefinition methodDefinition, Map<String, Object> arguments) {
        Parameter[] parameters = methodDefinition.method().getParameters();
        Object[] values = new Object[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            String argumentName = methodDefinition.argumentNames().get(index);
            Object rawValue = arguments.get(argumentName);
            values[index] = convertValue(parameters[index].getType(), rawValue);
        }
        return values;
    }

    private Object convertValue(Class<?> targetType, Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        if (String.class.equals(targetType)) {
            return String.valueOf(rawValue);
        }
        if (Long.class.equals(targetType) || long.class.equals(targetType)) {
            return Long.valueOf(String.valueOf(rawValue));
        }
        if (Integer.class.equals(targetType) || int.class.equals(targetType)) {
            return Integer.valueOf(String.valueOf(rawValue));
        }
        return rawValue;
    }

    private AgentActionExecutionOutput toExecutionOutput(Object rawResult, Map<String, Object> arguments) {
        if (rawResult instanceof AgentActionExecutionOutput output) {
            return output;
        }
        String text = rawResult == null ? "" : String.valueOf(rawResult);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resultText", text);
        payload.put("arguments", new LinkedHashMap<>(arguments));
        return AgentActionExecutionOutput.of(text, payload);
    }

    private String buildRequest(AgentPlanStepContext context) {
        return "description=" + context.step().description()
                + ", input=" + context.resolvedInput()
                + ", arguments=" + context.resolvedArguments();
    }

    private AgentStepExecutionResult fail(AgentPlanStepContext context, String request, String errorMessage) {
        return new AgentStepExecutionResult(
                context.step().stepNo(),
                context.step().action(),
                false,
                request,
                context.resolvedInput(),
                context.step().outputKey(),
                null,
                Map.of(),
                errorMessage,
                false
        );
    }
}
