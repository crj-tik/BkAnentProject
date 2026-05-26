package com.bkanent.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.common.agent.SessionMemoryResponse;
import com.bkanent.common.agent.SessionMemoryUpsertRequest;
import com.bkanent.memory.entity.SessionSharedMemoryEntity;
import com.bkanent.memory.mapper.SessionSharedMemoryMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
public class SessionSharedMemoryService {

    private final SessionSharedMemoryMapper sessionSharedMemoryMapper;
    private final ObjectMapper objectMapper;

    public SessionSharedMemoryService(SessionSharedMemoryMapper sessionSharedMemoryMapper,
                                      ObjectMapper objectMapper) {
        this.sessionSharedMemoryMapper = sessionSharedMemoryMapper;
        this.objectMapper = objectMapper;
    }

    public void upsert(SessionMemoryUpsertRequest request) {
        SessionSharedMemoryEntity entity = sessionSharedMemoryMapper.selectOne(
                new LambdaQueryWrapper<SessionSharedMemoryEntity>()
                        .eq(SessionSharedMemoryEntity::getSessionId, request.sessionId())
                        .last("limit 1")
        );
        if (entity == null) {
            entity = new SessionSharedMemoryEntity();
            entity.setSessionId(request.sessionId());
        }
        entity.setUserId(request.userId());
        entity.setSummary(request.summary());
        entity.setTraceId(request.traceId());
        entity.setMemoryJson(writeJson(request.memory() == null ? Map.of() : request.memory()));
        if (entity.getId() == null) {
            sessionSharedMemoryMapper.insert(entity);
        } else {
            sessionSharedMemoryMapper.updateById(entity);
        }
    }

    public Optional<SessionMemoryResponse> find(String sessionId) {
        SessionSharedMemoryEntity entity = sessionSharedMemoryMapper.selectOne(
                new LambdaQueryWrapper<SessionSharedMemoryEntity>()
                        .eq(SessionSharedMemoryEntity::getSessionId, sessionId)
                        .last("limit 1")
        );
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(new SessionMemoryResponse(
                entity.getSessionId(),
                entity.getUserId(),
                readJsonMap(entity.getMemoryJson()),
                entity.getSummary(),
                entity.getTraceId()
        ));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize session memory", exception);
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
            throw new IllegalStateException("Failed to deserialize session memory", exception);
        }
    }
}
