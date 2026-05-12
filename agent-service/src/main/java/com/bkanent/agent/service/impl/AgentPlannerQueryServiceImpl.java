package com.bkanent.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.agent.entity.AgentPlannerLogEntity;
import com.bkanent.agent.entity.AgentPlannerStepLogEntity;
import com.bkanent.agent.mapper.AgentPlannerLogMapper;
import com.bkanent.agent.mapper.AgentPlannerStepLogMapper;
import com.bkanent.agent.model.planner.AgentPlan;
import com.bkanent.agent.model.planner.AgentPlannerSessionLogResponse;
import com.bkanent.agent.model.planner.AgentPlannerStepLogResponse;
import com.bkanent.agent.service.AgentPlannerQueryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Agent Planner 日志查询服务实现。
 */
@Service
public class AgentPlannerQueryServiceImpl implements AgentPlannerQueryService {

    private final AgentPlannerLogMapper agentPlannerLogMapper;
    private final AgentPlannerStepLogMapper agentPlannerStepLogMapper;
    private final ObjectMapper objectMapper;

    public AgentPlannerQueryServiceImpl(AgentPlannerLogMapper agentPlannerLogMapper,
                                        AgentPlannerStepLogMapper agentPlannerStepLogMapper,
                                        ObjectMapper objectMapper) {
        this.agentPlannerLogMapper = agentPlannerLogMapper;
        this.agentPlannerStepLogMapper = agentPlannerStepLogMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentPlannerSessionLogResponse getSessionLog(String sessionNo) {
        AgentPlannerLogEntity plannerLogEntity = agentPlannerLogMapper.selectOne(
                new LambdaQueryWrapper<AgentPlannerLogEntity>()
                        .eq(AgentPlannerLogEntity::getSessionNo, sessionNo)
                        .eq(AgentPlannerLogEntity::getDeleted, 0)
                        .last("limit 1")
        );
        if (plannerLogEntity == null) {
            throw new IllegalArgumentException("未找到 Planner 会话日志: " + sessionNo);
        }
        List<AgentPlannerStepLogEntity> stepLogEntities = agentPlannerStepLogMapper.selectList(
                new LambdaQueryWrapper<AgentPlannerStepLogEntity>()
                        .eq(AgentPlannerStepLogEntity::getSessionNo, sessionNo)
                        .eq(AgentPlannerStepLogEntity::getDeleted, 0)
                        .orderByAsc(AgentPlannerStepLogEntity::getStepNo, AgentPlannerStepLogEntity::getId)
        );
        return new AgentPlannerSessionLogResponse(
                plannerLogEntity.getSessionNo(),
                plannerLogEntity.getExecutionMode(),
                plannerLogEntity.getUserMessage(),
                plannerLogEntity.getFinalAnswer(),
                plannerLogEntity.getPlanSummary(),
                readPlan(plannerLogEntity.getFinalPlanJson()),
                plannerLogEntity.getToolContext(),
                plannerLogEntity.getReplanCount(),
                plannerLogEntity.getCompleted() != null && plannerLogEntity.getCompleted() == 1,
                plannerLogEntity.getCreatedAt(),
                stepLogEntities.stream().map(this::toResponse).toList()
        );
    }

    private AgentPlannerStepLogResponse toResponse(AgentPlannerStepLogEntity entity) {
        return new AgentPlannerStepLogResponse(
                entity.getStepNo(),
                entity.getAction(),
                entity.getSuccess() != null && entity.getSuccess() == 1,
                entity.getSkipped() != null && entity.getSkipped() == 1,
                entity.getRequestContent(),
                entity.getResolvedInput(),
                entity.getOutputKey(),
                entity.getOutputContent(),
                readPayload(entity.getOutputPayloadJson()),
                entity.getErrorMessage()
        );
    }

    private AgentPlan readPlan(String finalPlanJson) {
        if (finalPlanJson == null || finalPlanJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(finalPlanJson, AgentPlan.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Planner 最终计划反序列化失败", exception);
        }
    }

    private Map<String, Object> readPayload(String outputPayloadJson) {
        if (outputPayloadJson == null || outputPayloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(outputPayloadJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("步骤输出负载反序列化失败", exception);
        }
    }
}
