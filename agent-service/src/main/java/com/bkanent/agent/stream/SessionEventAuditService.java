package com.bkanent.agent.stream;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.entity.AgentEventAuditEntity;
import com.bkanent.agent.mapper.AgentEventAuditMapper;
import com.bkanent.agent.model.distributed.SessionEventAuditView;
import com.bkanent.common.agent.SessionStreamEvent;
import com.bkanent.common.agent.SessionStreamEventTypes;
import com.bkanent.common.agent.SessionStreamVisibility;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SessionEventAuditService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionEventAuditService.class);

    private final DistributedAgentProperties distributedAgentProperties;
    private final AgentEventAuditMapper agentEventAuditMapper;
    private final ObjectMapper objectMapper;
    private final AtomicLong localFallbackSequence = new AtomicLong();

    public SessionEventAuditService(DistributedAgentProperties distributedAgentProperties,
                                    AgentEventAuditMapper agentEventAuditMapper,
                                    ObjectMapper objectMapper) {
        this.distributedAgentProperties = distributedAgentProperties;
        this.agentEventAuditMapper = agentEventAuditMapper;
        this.objectMapper = objectMapper;
    }

    public void record(SessionStreamEvent event) {
        recordAndEnrich(event);
    }

    /**
     * Persists an event before live delivery and returns its stable stream envelope.
     * The existing audit table id is used as the monotonic sequence within a
     * session/task. This avoids a second distributed counter while preserving
     * ordering for replay.
     */
    public SessionStreamEvent recordAndEnrich(SessionStreamEvent event) {
        try {
            return persistAndEnrich(event);
        } catch (RuntimeException exception) {
            LOGGER.debug("Session event audit unavailable; using local stream envelope", exception);
            return fallbackEnvelope(event);
        }
    }

    private SessionStreamEvent persistAndEnrich(SessionStreamEvent event) {
        if (event == null) {
            return null;
        }
        archiveExpired();
        String eventId = hasText(event.eventId()) ? event.eventId() : UUID.randomUUID().toString();
        AgentEventAuditEntity existing = agentEventAuditMapper.selectOne(
                new LambdaQueryWrapper<AgentEventAuditEntity>()
                        .eq(AgentEventAuditEntity::getEventId, eventId)
                        .last("limit 1")
        );
        if (existing != null) {
            return toEvent(existing);
        }
        SessionStreamEvent normalized = withEnvelope(event, eventId, null);
        AgentEventAuditEntity entity = new AgentEventAuditEntity();
        entity.setEventId(normalized.eventId());
        entity.setSessionId(normalized.sessionId());
        entity.setTaskId(normalized.taskId());
        entity.setAgentId(normalized.agentId());
        entity.setEventType(normalized.eventType());
        entity.setStage(resolveStage(normalized));
        entity.setContent(normalized.content());
        entity.setMetadataJson(writeJson(normalized.metadata()));
        entity.setTraceId(normalized.traceId());
        entity.setApprovalId(readMetadata(normalized.metadata(), "approvalId"));
        entity.setArtifactId(readMetadata(normalized.metadata(), "artifactId"));
        entity.setAsyncTaskId(readMetadata(normalized.metadata(), "asyncTaskId"));
        entity.setAsyncWorkflowId(readMetadata(normalized.metadata(), "asyncWorkflowId"));
        entity.setGrayStrategyVersion(readMetadata(normalized.metadata(), "grayStrategyVersion"));
        entity.setEventTimestamp(normalized.timestamp() == null ? System.currentTimeMillis() : normalized.timestamp());
        entity.setArchived(0);
        try {
            agentEventAuditMapper.insert(entity);
        } catch (DataIntegrityViolationException exception) {
            AgentEventAuditEntity concurrent = agentEventAuditMapper.selectOne(
                    new LambdaQueryWrapper<AgentEventAuditEntity>()
                            .eq(AgentEventAuditEntity::getEventId, eventId)
                            .last("limit 1")
            );
            if (concurrent != null) {
                return toEvent(concurrent);
            }
            throw exception;
        }
        trimOverflow();
        return withEnvelope(normalized, eventId, entity.getId());
    }

    private SessionStreamEvent fallbackEnvelope(SessionStreamEvent event) {
        if (event == null) {
            return null;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(event.metadata() == null ? Map.of() : event.metadata());
        String phase = firstText(event.phase(), readMetadata(metadata, "phase"),
                SessionStreamEventTypes.defaultPhase(event.eventType()));
        boolean terminal = event.terminal() != null
                ? event.terminal()
                : Boolean.TRUE.equals(readBoolean(metadata, "terminal", SessionStreamEventTypes.isTerminal(event.eventType())));
        String visibility = firstText(event.visibility(), readMetadata(metadata, "visibility"),
                SessionStreamVisibility.PROGRESS);
        putIfMissing(metadata, "phase", phase);
        putIfMissing(metadata, "terminal", terminal);
        putIfMissing(metadata, "visibility", visibility);
        copyIfPresent(metadata, "childRunId", event.childRunId());
        copyIfPresent(metadata, "parentTaskId", event.parentTaskId());
        copyIfPresent(metadata, "branchId", event.branchId());
        return new SessionStreamEvent(
                event.sessionId(), event.taskId(), event.agentId(), event.eventType(), event.content(),
                immutableMetadata(metadata), event.traceId(), event.timestamp() == null ? System.currentTimeMillis() : event.timestamp(),
                hasText(event.eventId()) ? event.eventId() : UUID.randomUUID().toString(),
                event.sequence() == null ? localFallbackSequence.incrementAndGet() : event.sequence(),
                firstText(event.childRunId(), readMetadata(metadata, "childRunId"), null),
                firstText(event.parentTaskId(), readMetadata(metadata, "parentTaskId"), null),
                firstText(event.branchId(), readMetadata(metadata, "branchId"), null),
                phase, terminal, visibility
        );
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

    public List<SessionStreamEvent> replay(String sessionId,
                                           String taskId,
                                           String afterEventId,
                                           Long afterSequence,
                                           Integer limit) {
        try {
            return persistedReplay(sessionId, taskId, afterEventId, afterSequence, limit);
        } catch (RuntimeException exception) {
            LOGGER.debug("Session event audit replay unavailable; continuing with live local stream", exception);
            return List.of();
        }
    }

    private List<SessionStreamEvent> persistedReplay(String sessionId,
                                                     String taskId,
                                                     String afterEventId,
                                                     Long afterSequence,
                                                     Integer limit) {
        archiveExpired();
        int size = limit == null || limit <= 0 ? 200 : Math.min(limit, 1000);
        Long cursor = afterSequence;
        if (cursor == null && hasText(afterEventId)) {
            AgentEventAuditEntity cursorEntity = agentEventAuditMapper.selectOne(
                    new LambdaQueryWrapper<AgentEventAuditEntity>()
                            .eq(AgentEventAuditEntity::getEventId, afterEventId)
                            .last("limit 1")
            );
            cursor = cursorEntity == null ? null : cursorEntity.getId();
        }
        LambdaQueryWrapper<AgentEventAuditEntity> wrapper = new LambdaQueryWrapper<AgentEventAuditEntity>()
                .eq(AgentEventAuditEntity::getSessionId, sessionId)
                .orderByAsc(AgentEventAuditEntity::getId)
                .last("limit " + size);
        if (hasText(taskId)) {
            wrapper.eq(AgentEventAuditEntity::getTaskId, taskId);
        }
        if (cursor != null && cursor > 0) {
            wrapper.gt(AgentEventAuditEntity::getId, cursor);
        }
        return agentEventAuditMapper.selectList(wrapper).stream().map(this::toEvent).toList();
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
                entity.getEventId() == null ? "legacy-" + entity.getId() : entity.getEventId(),
                entity.getId(),
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

    private SessionStreamEvent toEvent(AgentEventAuditEntity entity) {
        String eventId = entity.getEventId() == null ? "legacy-" + entity.getId() : entity.getEventId();
        Map<String, Object> metadata = readJson(entity.getMetadataJson());
        String phase = firstText(readMetadata(metadata, "phase"), null,
                SessionStreamEventTypes.defaultPhase(entity.getEventType()));
        String visibility = firstText(readMetadata(metadata, "visibility"), null,
                SessionStreamVisibility.PROGRESS);
        return new SessionStreamEvent(
                entity.getSessionId(),
                entity.getTaskId(),
                entity.getAgentId(),
                entity.getEventType(),
                entity.getContent(),
                metadata,
                entity.getTraceId(),
                entity.getEventTimestamp(),
                eventId,
                entity.getId(),
                readMetadata(metadata, "childRunId"),
                readMetadata(metadata, "parentTaskId"),
                readMetadata(metadata, "branchId"),
                phase,
                readBoolean(metadata, "terminal", SessionStreamEventTypes.isTerminal(entity.getEventType())),
                visibility
        );
    }

    private SessionStreamEvent withEnvelope(SessionStreamEvent event, String eventId, Long sequence) {
        Map<String, Object> metadata = new LinkedHashMap<>(event.metadata() == null ? Map.of() : event.metadata());
        String phase = firstText(event.phase(), readMetadata(metadata, "phase"),
                SessionStreamEventTypes.defaultPhase(event.eventType()));
        boolean terminal = event.terminal() != null
                ? event.terminal()
                : Boolean.TRUE.equals(readBoolean(metadata, "terminal", SessionStreamEventTypes.isTerminal(event.eventType())));
        String visibility = firstText(event.visibility(), readMetadata(metadata, "visibility"),
                SessionStreamVisibility.PROGRESS);
        putIfMissing(metadata, "phase", phase);
        putIfMissing(metadata, "terminal", terminal);
        putIfMissing(metadata, "visibility", visibility);
        copyIfPresent(metadata, "childRunId", event.childRunId());
        copyIfPresent(metadata, "parentTaskId", event.parentTaskId());
        copyIfPresent(metadata, "branchId", event.branchId());
        return new SessionStreamEvent(
                event.sessionId(), event.taskId(), event.agentId(), event.eventType(), event.content(),
                immutableMetadata(metadata), event.traceId(), event.timestamp() == null ? System.currentTimeMillis() : event.timestamp(),
                eventId, sequence, firstText(event.childRunId(), readMetadata(metadata, "childRunId"), null),
                firstText(event.parentTaskId(), readMetadata(metadata, "parentTaskId"), null),
                firstText(event.branchId(), readMetadata(metadata, "branchId"), null), phase, terminal, visibility
        );
    }

    private String firstText(String first, String second, String fallback) {
        return hasText(first) ? first : hasText(second) ? second : fallback;
    }

    private boolean hasText(String value) {
        return StringUtils.hasText(value);
    }

    private void putIfMissing(Map<String, Object> values, String key, Object value) {
        values.putIfAbsent(key, value);
    }

    private void copyIfPresent(Map<String, Object> values, String key, String value) {
        if (hasText(value)) {
            values.putIfAbsent(key, value);
        }
    }

    private Boolean readBoolean(Map<String, Object> metadata, String key, boolean fallback) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
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
            return parsed == null ? Map.of() : immutableMetadata(parsed);
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

    private Map<String, Object> immutableMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key != null && value != null) {
                sanitized.put(key, value);
            }
        });
        return sanitized.isEmpty() ? Map.of() : Collections.unmodifiableMap(sanitized);
    }
}
