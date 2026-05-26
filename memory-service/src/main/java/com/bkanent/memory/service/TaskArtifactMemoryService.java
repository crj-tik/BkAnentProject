package com.bkanent.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.common.agent.ArtifactCreateRequest;
import com.bkanent.common.agent.ArtifactMeta;
import com.bkanent.common.agent.ArtifactQueryResponse;
import com.bkanent.memory.entity.TaskArtifactMemoryEntity;
import com.bkanent.memory.mapper.TaskArtifactMemoryMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TaskArtifactMemoryService {

    private final TaskArtifactMemoryMapper taskArtifactMemoryMapper;
    private final ObjectMapper objectMapper;

    public TaskArtifactMemoryService(TaskArtifactMemoryMapper taskArtifactMemoryMapper,
                                     ObjectMapper objectMapper) {
        this.taskArtifactMemoryMapper = taskArtifactMemoryMapper;
        this.objectMapper = objectMapper;
    }

    public ArtifactQueryResponse create(ArtifactCreateRequest request) {
        TaskArtifactMemoryEntity entity = new TaskArtifactMemoryEntity();
        entity.setArtifactId(UUID.randomUUID().toString());
        entity.setTaskId(request.taskId());
        entity.setSessionId(request.sessionId());
        entity.setAgentId(request.agentId());
        entity.setArtifactType(request.artifactType());
        entity.setVersionNo(request.version() == null ? 1 : request.version());
        entity.setContentJson(writeJson(request.content()));
        entity.setMetadataJson(writeJson(request.metadata() == null ? Map.of() : request.metadata()));
        entity.setTraceId(request.traceId());
        taskArtifactMemoryMapper.insert(entity);
        return toQueryResponse(entity);
    }

    public List<ArtifactQueryResponse> listByTaskId(String taskId, String sessionId) {
        return taskArtifactMemoryMapper.selectList(new LambdaQueryWrapper<TaskArtifactMemoryEntity>()
                        .eq(TaskArtifactMemoryEntity::getTaskId, taskId)
                        .eq(sessionId != null && !sessionId.isBlank(), TaskArtifactMemoryEntity::getSessionId, sessionId)
                        .orderByAsc(TaskArtifactMemoryEntity::getId))
                .stream()
                .map(this::toQueryResponse)
                .toList();
    }

    public ArtifactQueryResponse getByArtifactId(String artifactId, String taskId, String sessionId) {
        TaskArtifactMemoryEntity entity = taskArtifactMemoryMapper.selectOne(
                new LambdaQueryWrapper<TaskArtifactMemoryEntity>()
                        .eq(TaskArtifactMemoryEntity::getArtifactId, artifactId)
                        .eq(taskId != null && !taskId.isBlank(), TaskArtifactMemoryEntity::getTaskId, taskId)
                        .eq(sessionId != null && !sessionId.isBlank(), TaskArtifactMemoryEntity::getSessionId, sessionId)
                        .last("limit 1")
        );
        return entity == null ? null : toQueryResponse(entity);
    }

    private ArtifactQueryResponse toQueryResponse(TaskArtifactMemoryEntity entity) {
        return new ArtifactQueryResponse(
                new ArtifactMeta(
                        entity.getArtifactId(),
                        entity.getTaskId(),
                        entity.getSessionId(),
                        entity.getAgentId(),
                        entity.getArtifactType(),
                        entity.getVersionNo(),
                        readJsonMap(entity.getMetadataJson()),
                        entity.getTraceId(),
                        entity.getCreatedAt()
                ),
                readJsonObject(entity.getContentJson())
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize artifact content", exception);
        }
    }

    private Map<String, Object> readJsonMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize artifact metadata", exception);
        }
    }

    private Object readJsonObject(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize artifact content", exception);
        }
    }
}
