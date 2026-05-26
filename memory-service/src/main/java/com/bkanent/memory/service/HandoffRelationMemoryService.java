package com.bkanent.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.common.agent.AgentHandoffPacket;
import com.bkanent.common.agent.HandoffRelationQueryResponse;
import com.bkanent.memory.entity.HandoffRelationMemoryEntity;
import com.bkanent.memory.mapper.HandoffRelationMemoryMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class HandoffRelationMemoryService {

    private final HandoffRelationMemoryMapper handoffRelationMemoryMapper;
    private final ObjectMapper objectMapper;

    public HandoffRelationMemoryService(HandoffRelationMemoryMapper handoffRelationMemoryMapper,
                                        ObjectMapper objectMapper) {
        this.handoffRelationMemoryMapper = handoffRelationMemoryMapper;
        this.objectMapper = objectMapper;
    }

    public HandoffRelationQueryResponse create(AgentHandoffPacket packet) {
        HandoffRelationMemoryEntity entity = new HandoffRelationMemoryEntity();
        entity.setHandoffId(UUID.randomUUID().toString());
        entity.setSessionId(packet.sessionId());
        entity.setTaskId(packet.taskId());
        entity.setParentTaskId(packet.parentTaskId());
        entity.setTraceId(packet.traceId());
        entity.setFromAgent(packet.fromAgent());
        entity.setToAgent(packet.toAgent());
        entity.setReason(packet.reason());
        entity.setUserGoal(packet.userGoal());
        entity.setStructuredContextJson(writeJson(packet.structuredContext() == null ? Map.of() : packet.structuredContext()));
        entity.setArtifactIdsJson(writeJson(packet.artifactIds() == null ? List.of() : packet.artifactIds()));
        entity.setConstraintsJson(writeJson(packet.constraints() == null ? List.of() : packet.constraints()));
        entity.setExpectedOutput(packet.expectedOutput());
        handoffRelationMemoryMapper.insert(entity);
        return toResponse(entity);
    }

    public List<HandoffRelationQueryResponse> listByTaskId(String taskId) {
        return handoffRelationMemoryMapper.selectList(new LambdaQueryWrapper<HandoffRelationMemoryEntity>()
                        .eq(HandoffRelationMemoryEntity::getTaskId, taskId)
                        .orderByAsc(HandoffRelationMemoryEntity::getId))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public List<HandoffRelationQueryResponse> listBySessionId(String sessionId) {
        return handoffRelationMemoryMapper.selectList(new LambdaQueryWrapper<HandoffRelationMemoryEntity>()
                        .eq(HandoffRelationMemoryEntity::getSessionId, sessionId)
                        .orderByAsc(HandoffRelationMemoryEntity::getId))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private HandoffRelationQueryResponse toResponse(HandoffRelationMemoryEntity entity) {
        return new HandoffRelationQueryResponse(
                new AgentHandoffPacket(
                        entity.getSessionId(),
                        entity.getTaskId(),
                        entity.getParentTaskId(),
                        entity.getTraceId(),
                        entity.getFromAgent(),
                        entity.getToAgent(),
                        entity.getReason(),
                        entity.getUserGoal(),
                        readJsonMap(entity.getStructuredContextJson()),
                        readJsonList(entity.getArtifactIdsJson()),
                        readJsonList(entity.getConstraintsJson()),
                        entity.getExpectedOutput()
                ),
                entity.getCreatedAt()
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize handoff relation", exception);
        }
    }

    private Map<String, Object> readJsonMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize handoff relation context", exception);
        }
    }

    private List<String> readJsonList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize handoff relation list", exception);
        }
    }
}
