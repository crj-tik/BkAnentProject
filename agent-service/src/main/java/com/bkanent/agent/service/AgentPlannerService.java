package com.bkanent.agent.service;

import com.bkanent.agent.model.planner.AgentPlan;
import com.bkanent.agent.model.planner.AgentStepExecutionResult;

import java.util.List;

/**
 * Agent 规划服务接口。
 */
public interface AgentPlannerService {

    /**
     * 业务方法：createPlan。
     */
    AgentPlan createPlan(String userMessage, List<AgentStepExecutionResult> completedResults, AgentStepExecutionResult failedResult);

    /**
     * 业务方法：buildFinalAnswer。
     */
    String buildFinalAnswer(String userMessage, List<AgentStepExecutionResult> executionResults, boolean completed);
}
