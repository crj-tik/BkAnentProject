package com.bkanent.agent.service;

import com.bkanent.agent.model.planner.AgentPlannerSession;

/**
 * Planner 执行器服务接口。
 */
public interface AgentPlanExecutorService {

    /**
     * 业务方法：execute。
     */
    AgentPlannerSession execute(String userMessage);
}
