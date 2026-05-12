package com.bkanent.agent.service;

import com.bkanent.agent.enums.AgentExecutionMode;
import com.bkanent.agent.model.planner.AgentPlannerSession;

/**
 * Planner 日志持久化服务接口。
 */
public interface AgentPlannerLogPersistenceService {

    void savePlannerSession(String sessionNo,
                            AgentExecutionMode executionMode,
                            String userMessage,
                            String finalAnswer,
                            String toolContext,
                            AgentPlannerSession plannerSession);
}
