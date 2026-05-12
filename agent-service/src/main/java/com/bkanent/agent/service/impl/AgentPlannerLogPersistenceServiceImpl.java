package com.bkanent.agent.service.impl;

import com.bkanent.agent.entity.AgentPlannerLogEntity;
import com.bkanent.agent.entity.AgentPlannerStepLogEntity;
import com.bkanent.agent.enums.AgentExecutionMode;
import com.bkanent.agent.mapper.AgentPlannerLogMapper;
import com.bkanent.agent.mapper.AgentPlannerStepLogMapper;
import com.bkanent.agent.model.planner.AgentPlannerSession;
import com.bkanent.agent.model.planner.AgentStepExecutionResult;
import com.bkanent.agent.service.AgentPlannerLogPersistenceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent Planner 日志持久化服务实现。
 */
@Service
public class AgentPlannerLogPersistenceServiceImpl implements AgentPlannerLogPersistenceService {

    private final AgentPlannerLogMapper agentPlannerLogMapper;
    private final AgentPlannerStepLogMapper agentPlannerStepLogMapper;
    private final ObjectMapper objectMapper;

    public AgentPlannerLogPersistenceServiceImpl(AgentPlannerLogMapper agentPlannerLogMapper,
                                                 AgentPlannerStepLogMapper agentPlannerStepLogMapper,
                                                 ObjectMapper objectMapper) {
        this.agentPlannerLogMapper = agentPlannerLogMapper;
        this.agentPlannerStepLogMapper = agentPlannerStepLogMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void savePlannerSession(String sessionNo,
                                   AgentExecutionMode executionMode,
                                   String userMessage,
                                   String finalAnswer,
                                   String toolContext,
                                   AgentPlannerSession plannerSession) {
        if (plannerSession == null) {
            return;
        }
        AgentPlannerLogEntity plannerLogEntity = new AgentPlannerLogEntity();
        plannerLogEntity.setSessionNo(sessionNo);
        plannerLogEntity.setExecutionMode(executionMode.name());
        plannerLogEntity.setUserMessage(userMessage);
        plannerLogEntity.setFinalAnswer(finalAnswer);
        plannerLogEntity.setToolContext(toolContext);
        plannerLogEntity.setReplanCount(plannerSession.replanCount());
        plannerLogEntity.setCompleted(plannerSession.completed() ? 1 : 0);
        plannerLogEntity.setPlanSummary(plannerSession.finalPlan() == null ? null : plannerSession.finalPlan().summary());
        plannerLogEntity.setFinalPlanJson(writeJson(plannerSession.finalPlan()));
        agentPlannerLogMapper.insert(plannerLogEntity);

        for (AgentStepExecutionResult executionResult : plannerSession.executionResults()) {
            AgentPlannerStepLogEntity stepLogEntity = new AgentPlannerStepLogEntity();
            stepLogEntity.setPlannerLogId(plannerLogEntity.getId());
            stepLogEntity.setSessionNo(sessionNo);
            stepLogEntity.setStepNo(executionResult.stepNo());
            stepLogEntity.setAction(executionResult.action());
            stepLogEntity.setSuccess(executionResult.success() ? 1 : 0);
            stepLogEntity.setSkipped(executionResult.skipped() ? 1 : 0);
            stepLogEntity.setRequestContent(executionResult.request());
            stepLogEntity.setResolvedInput(executionResult.resolvedInput());
            stepLogEntity.setOutputKey(executionResult.outputKey());
            stepLogEntity.setOutputContent(executionResult.output());
            stepLogEntity.setOutputPayloadJson(writeJson(executionResult.outputPayload()));
            stepLogEntity.setErrorMessage(executionResult.errorMessage());
            agentPlannerStepLogMapper.insert(stepLogEntity);
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Planner 日志序列化失败", exception);
        }
    }
}
