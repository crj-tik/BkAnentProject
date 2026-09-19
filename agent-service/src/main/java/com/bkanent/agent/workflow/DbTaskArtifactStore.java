package com.bkanent.agent.workflow;

import com.bkanent.agent.entity.AgentTaskArtifactEntity;
import com.bkanent.agent.mapper.AgentTaskArtifactMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

/**
 * DbTaskArtifactStore 数据库版任务产物存储。
 */
@Component
public class DbTaskArtifactStore implements TaskArtifactStore {

    private final AgentTaskArtifactMapper artifactMapper;
    private final ObjectMapper objectMapper;

    public DbTaskArtifactStore(AgentTaskArtifactMapper artifactMapper,
                               ObjectMapper objectMapper) {
        this.artifactMapper = artifactMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public String save(String taskId,
                       String sessionId,
                       String agentId,
                       String artifactType,
                       Integer versionNo,
                       Object content,
                       Map<String, Object> metadata,
                       String traceId) {
        AgentTaskArtifactEntity entity = new AgentTaskArtifactEntity();
        entity.setArtifactId(UUID.randomUUID().toString());
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setAgentId(agentId);
        entity.setArtifactType(artifactType);
        entity.setVersionNo(versionNo);
        entity.setContentJson(writeJson(content));
        entity.setMetadataJson(writeJson(metadata == null ? Map.of() : metadata));
        artifactMapper.insert(entity);
        return entity.getArtifactId();
    }

    @Override
    public Optional<String> findBySourceArtifactId(String taskId,
                                                   String sessionId,
                                                   String agentId,
                                                   String sourceArtifactId) {
        if (sourceArtifactId == null || sourceArtifactId.isBlank()) {
            return Optional.empty();
        }
        AgentTaskArtifactEntity entity = artifactMapper.selectOne(new LambdaQueryWrapper<AgentTaskArtifactEntity>()
                .eq(AgentTaskArtifactEntity::getTaskId, taskId)
                .eq(AgentTaskArtifactEntity::getSessionId, sessionId)
                .eq(AgentTaskArtifactEntity::getAgentId, agentId)
                .like(AgentTaskArtifactEntity::getMetadataJson,
                        "\"sourceArtifactId\":\"" + sourceArtifactId + "\"")
                .last("limit 1"));
        return entity == null ? Optional.empty() : Optional.of(entity.getArtifactId());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize task artifact", exception);
        }
    }
}
