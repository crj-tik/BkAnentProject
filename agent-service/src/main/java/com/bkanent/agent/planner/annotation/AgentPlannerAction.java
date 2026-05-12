package com.bkanent.agent.planner.annotation;

import com.bkanent.agent.mcp.AgentToolExecutionType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Planner action metadata.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentPlannerAction {

    String action();

    String inputDescription();

    String outputDescription();

    String[] requiredArguments() default {};

    String exampleArguments() default "{}";

    String inputSchema() default "{\"type\":\"object\"}";

    String outputSchema() default "{\"type\":\"string\"}";

    String exampleOutput() default "";

    AgentToolExecutionType executionType() default AgentToolExecutionType.LOCAL_BEAN;

    String mcpServerName() default "";

    String mcpToolName() default "";
}
