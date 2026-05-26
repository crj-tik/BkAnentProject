package com.bkanent.agent.stream;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.entity.AgentEventAuditEntity;
import com.bkanent.agent.mapper.AgentEventAuditMapper;
import com.bkanent.agent.model.distributed.SessionEventAuditView;
import com.bkanent.common.agent.SessionStreamEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SessionEventAuditService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DistributedAgentProperties distributedAgentProperties;
    private final AgentEventAuditMapper agentEventAuditMapper;
    private final ObjectMapper objectMapper;

    public SessionEventAuditService(DistributedAgentProperties distributedAgentProperties,
                                    AgentEventAuditMapper agentEventAuditMapper,
                                    ObjectMapper objectMapper) {
        this.distributedAgentProperties = distributedAgentProperties;
        this.agentEventAuditMapper = agentEventAuditMapper;
        this.objectMapper = objectMapper;
    }

    public void record(SessionStreamEvent event) {
        if (event == null) {
            return;
        }
        archiveExpired();
        AgentEventAuditEntity entity = new AgentEventAuditEntity();
        entity.setSessionId(event.sessionId());
        entity.setTaskId(event.taskId());
        entity.setAgentId(event.agentId());
        entity.setEventType(event.eventType());
        entity.setStage(resolveStage(event));
        entity.setContent(event.content());
        entity.setMetadataJson(writeJson(event.metadata()));
        entity.setTraceId(event.traceId());
        entity.setApprovalId(readMetadata(event.metadata(), "approvalId"));
        entity.setArtifactId(readMetadata(event.metadata(), "artifactId"));
        entity.setAsyncTaskId(readMetadata(event.metadata(), "asyncTaskId"));
        entity.setAsyncWorkflowId(readMetadata(event.metadata(), "asyncWorkflowId"));
        entity.setGrayStrategyVersion(readMetadata(event.metadata(), "grayStrategyVersion"));
        entity.setEventTimestamp(event.timestamp() == null ? System.currentTimeMillis() : event.timestamp());
        entity.setArchived(0);
        agentEventAuditMapper.insert(entity);
        trimOverflow();
    }

    public List<SessionEventAuditView> query(String taskId,
                                             String traceId,
                                             String approvalId,
                                             String artifactId,
                                             String asyncTaskId,
                                             String grayStrategyVersion,
                                             String asyncWorkflowId,
                                             Boolean includeArchived,
                                             Integer limit) {
        archiveExpired();
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        LambdaQueryWrapper<AgentEventAuditEntity> wrapper = new LambdaQueryWrapper<AgentEventAuditEntity>()
                .orderByDesc(AgentEventAuditEntity::getEventTimestamp)
                .last("limit " + size);
        if (StringUtils.hasText(taskId)) {
            wrapper.eq(AgentEventAuditEntity::getTaskId, taskId);
        }
        if (StringUtils.hasText(traceId)) {
            wrapper.eq(AgentEventAuditEntity::getTraceId, traceId);
        }
        if (StringUtils.hasText(approvalId)) {
            wrapper.eq(AgentEventAuditEntity::getApprovalId, approvalId);
        }
        if (StringUtils.hasText(artifactId)) {
            wrapper.eq(AgentEventAuditEntity::getArtifactId, artifactId);
        }
        if (StringUtils.hasText(asyncTaskId)) {
            wrapper.eq(AgentEventAuditEntity::getAsyncTaskId, asyncTaskId);
        }
        if (StringUtils.hasText(grayStrategyVersion)) {
            wrapper.eq(AgentEventAuditEntity::getGrayStrategyVersion, grayStrategyVersion);
        }
        if (StringUtils.hasText(asyncWorkflowId)) {
            wrapper.eq(AgentEventAuditEntity::getAsyncWorkflowId, asyncWorkflowId);
        }
        if (!Boolean.TRUE.equals(includeArchived)) {
            wrapper.eq(AgentEventAuditEntity::getArchived, 0);
        }
        return agentEventAuditMapper.selectList(wrapper).stream().map(this::toView).toList();
    }

    public Map<String, Object> summarize() {
        archiveExpired();
        DistributedAgentProperties.EventAuditProperties properties = distributedAgentProperties.getEventAudit();
        long activeCount = agentEventAuditMapper.selectCount(
                new LambdaQueryWrapper<AgentEventAuditEntity>()
                        .eq(AgentEventAuditEntity::getArchived, 0)
        );
        long archivedCount = agentEventAuditMapper.selectCount(
                new LambdaQueryWrapper<AgentEventAuditEntity>()
                        .eq(AgentEventAuditEntity::getArchived, 1)
        );
        return Map.of(
                "activeEventCount", activeCount,
                "archivedEventCount", archivedCount,
                "maxActiveEvents", properties.getMaxActiveEvents(),
                "archiveEnabled", properties.isArchiveEnabled(),
                "maxArchivedEvents", properties.getMaxArchivedEvents(),
                "retentionSeconds", properties.getRetentionSeconds()
        );
    }

    private void trimOverflow() {
        DistributedAgentProperties.EventAuditProperties properties = distributedAgentProperties.getEventAudit();
        long activeCount = agentEventAuditMapper.selectCount(
                new LambdaQueryWrapper<AgentEventAuditEntity>().eq(AgentEventAuditEntity::getArchived, 0)
        );
        if (activeCount <= properties.getMaxActiveEvents()) {
            return;
        }
        long overflow = activeCount - properties.getMaxActiveEvents();
        List<AgentEventAuditEntity> expired = agentEventAuditMapper.selectList(
                new LambdaQueryWrapper<AgentEventAuditEntity>()
                        .eq(AgentEventAuditEntity::getArchived, 0)
                        .orderByAsc(AgentEventAuditEntity::getEventTimestamp)
                        .last("limit " + overflow)
        );
        long now = System.currentTimeMillis();
        for (AgentEventAuditEntity entity : expired) {
            if (!properties.isArchiveEnabled()) {
                agentEventAuditMapper.deleteById(entity.getId());
                continue;
            }
            entity.setArchived(1);
            entity.setArchivedAtMs(now);
            agentEventAuditMapper.updateById(entity);
        }
        trimArchivedOverflow();
    }

    private void trimArchivedOverflow() {
        DistributedAgentProperties.EventAuditProperties properties = distributedAgentProperties.getEventAudit();
        long archivedCount = agentEventAuditMapper.selectCount(
                new LambdaQueryWrapper<AgentEventAuditEntity>().eq(AgentEventAuditEntity::getArchived, 1)
        );
        if (archivedCount <= properties.getMaxArchivedEvents()) {
            return;
        }
        long overflow = archivedCount - properties.getMaxArchivedEvents();
        List<AgentEventAuditEntity> expired = agentEventAuditMapper.selectList(
                new LambdaQueryWrapper<AgentEventAuditEntity>()
                        .eq(AgentEventAuditEntity::getArchived, 1)
                        .orderByAsc(AgentEventAuditEntity::getArchivedAtMs)
                        .last("limit " + overflow)
        );
        for (AgentEventAuditEntity entity : expired) {
            agentEventAuditMapper.deleteById(entity.getId());
        }
    }

    private void archiveExpired() {
        DistributedAgentProperties.EventAuditProperties properties = distributedAgentProperties.getEventAudit();
        long retentionMs = Math.max(60_000L, properties.getRetentionSeconds() * 1000L);
        long expireBefore = System.currentTimeMillis() - retentionMs;
        if (properties.isArchiveEnabled()) {
            agentEventAuditMapper.update(
                    null,
                    new LambdaUpdateWrapper<AgentEventAuditEntity>()
                            .eq(AgentEventAuditEntity::getArchived, 0)
                            .lt(AgentEventAuditEntity::getEventTimestamp, expireBefore)
                            .set(AgentEventAuditEntity::getArchived, 1)
                            .set(AgentEventAuditEntity::getArchivedAtMs, System.currentTimeMillis())
            );
        } else {
            agentEventAuditMapper.delete(
                    new LambdaQueryWrapper<AgentEventAuditEntity>()
                            .eq(AgentEventAuditEntity::getArchived, 0)
                            .lt(AgentEventAuditEntity::getEventTimestamp, expireBefore)
            );
        }
        agentEventAuditMapper.delete(
                new LambdaQueryWrapper<AgentEventAuditEntity>()
                        .eq(AgentEventAuditEntity::getArchived, 1)
                        .lt(AgentEventAuditEntity::getEventTimestamp, expireBefore)
        );
        trimArchivedOverflow();
    }

    private SessionEventAuditView toView(AgentEventAuditEntity entity) {
        return new SessionEventAuditView(
                entity.getSessionId(),
                entity.getTaskId(),
                entity.getAgentId(),
                entity.getEventType(),
                entity.getStage(),
                entity.getContent(),
                readJson(entity.getMetadataJson()),
                entity.getTraceId(),
                entity.getEventTimestamp()
        );
    }

    private String resolveStage(SessionStreamEvent event) {
        Map<String, Object> metadata = event.metadata() == null ? Map.of() : event.metadata();
        Object explicit = metadata.get("stage");
        if (explicit != null && StringUtils.hasText(String.valueOf(explicit))) {
            return String.valueOf(explicit);
        }
        String eventType = event.eventType();
        if (!StringUtils.hasText(eventType)) {
            return "generic";
        }
        int separator = eventType.lastIndexOf('.');
        return separator > 0 ? eventType.substring(0, separator) : eventType;
    }

    private String writeJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize event metadata", exception);
        }
    }

    private Map<String, Object> readJson(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
            return parsed == null ? Map.of() : Map.copyOf(parsed);
        } catch (Exception exception) {
            return Map.of("raw", json);
        }
    }

    private String readMetadata(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null || !StringUtils.hasText(String.valueOf(value)) ? null : String.valueOf(value);
    }
}
