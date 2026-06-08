package com.bkanent.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.common.agent.SessionMemoryResponse;
import com.bkanent.common.agent.SessionMemorySnapshotRequest;
import com.bkanent.memory.entity.SessionMemorySnapshotEntity;
import com.bkanent.memory.mapper.SessionMemorySnapshotMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
public class SessionMemorySnapshotService {

    private final SessionMemorySnapshotMapper mapper;
    private final ObjectMapper objectMapper;

    public SessionMemorySnapshotService(SessionMemorySnapshotMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void save(SessionMemorySnapshotRequest request) {
        SessionMemorySnapshotEntity entity = new SessionMemorySnapshotEntity();
        entity.setSessionId(request.sessionId());
        entity.setTaskId(request.taskId());
        entity.setVersion(request.version() != null ? request.version() : 1);
        entity.setMemoryJson(writeJson(request.memory() == null ? Map.of() : request.memory()));
        entity.setSummary(request.summary());
        entity.setTraceId(request.traceId());
        mapper.insert(entity);
    }

    public Optional<SessionMemoryResponse> getLatest(String sessionId) {
        SessionMemorySnapshotEntity entity = mapper.selectOne(
                new LambdaQueryWrapper<SessionMemorySnapshotEntity>()
                        .eq(SessionMemorySnapshotEntity::getSessionId, sessionId)
                        .orderByDesc(SessionMemorySnapshotEntity::getVersion)
                        .last("limit 1")
        );
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(new SessionMemoryResponse(
                entity.getSessionId(),
                null,
                readJsonMap(entity.getMemoryJson()),
                entity.getSummary(),
                entity.getTraceId(),
                entity.getVersion()
        ));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize snapshot memory", e);
        }
    }

    private Map<String, Object> readJsonMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize snapshot memory", e);
        }
    }
}
