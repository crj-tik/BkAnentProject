package com.bkanent.agent.service;

import com.bkanent.agent.model.planner.AgentPlannerSessionLogResponse;

/**
 * Agent 规划查询服务接口。
 */
public interface AgentPlannerQueryService {

    /**
     * 业务方法：getSessionLog。
     */
    AgentPlannerSessionLogResponse getSessionLog(String sessionNo);
}
