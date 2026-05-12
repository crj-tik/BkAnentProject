package com.bkanent.agent.planner.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Agent Planner 閸欏倹鏆熷▔銊ㄐ掗妴? */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentPlannerParam {

    String value();
}


