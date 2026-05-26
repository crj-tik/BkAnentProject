package com.bkanent.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.entity.AgentGovernanceOverrideEntity;
import com.bkanent.agent.mapper.AgentGovernanceOverrideMapper;
import com.bkanent.agent.model.distributed.GovernanceGrayReleaseOverrideRequest;
import com.bkanent.agent.model.distributed.GovernanceRateLimitOverrideRequest;
import com.bkanent.agent.model.distributed.SupervisorGovernanceView;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.stream.SessionEventAuditService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SupervisorGovernanceService {

    private static final String OVERRIDE_TYPE_RATE_LIMIT = "RATE_LIMIT";
    private static final String OVERRIDE_TYPE_GRAY_RELEASE = "GRAY_RELEASE";
    private static final String OVERRIDE_KEY_GRAY_GLOBAL = "global";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DistributedAgentProperties distributedAgentProperties;
    private final SupervisorRateLimiter supervisorRateLimiter;
    private final SessionEventAuditService sessionEventAuditService;
    private final AgentGovernanceOverrideMapper governanceOverrideMapper;
    private final ObjectMapper objectMapper;

    public SupervisorGovernanceService(DistributedAgentProperties distributedAgentProperties,
                                       SupervisorRateLimiter supervisorRateLimiter,
                                       SessionEventAuditService sessionEventAuditService,
                                       AgentGovernanceOverrideMapper governanceOverrideMapper,
                                       ObjectMapper objectMapper) {
        this.distributedAgentProperties = distributedAgentProperties;
        this.supervisorRateLimiter = supervisorRateLimiter;
        this.sessionEventAuditService = sessionEventAuditService;
        this.governanceOverrideMapper = governanceOverrideMapper;
        this.objectMapper = objectMapper;
    }

    public void assertRateLimit(String entryType, SupervisorTaskRequest request) {
        DistributedAgentProperties.RateLimitProperties rateLimit = distributedAgentProperties.getRateLimit();
        if (!rateLimit.isEnabled()) {
            return;
        }
        int limit = resolveLimit(entryType, rateLimit);
        if (limit <= 0) {
            return;
        }
        long windowMs = Math.max(1000L, rateLimit.getWindowSeconds() * 1000L);
        String identity = resolveIdentity(request);
        String key = entryType + ":" + identity;
        if (!supervisorRateLimiter.tryAcquire(key, limit, windowMs)) {
            throw new IllegalStateException("rate limit exceeded for " + entryType);
        }
    }

    public SupervisorTaskRequest applyGrayContext(SupervisorTaskRequest request) {
        DistributedAgentProperties.GrayReleaseProperties gray = distributedAgentProperties.getGrayRelease();
        PersistentGrayOverride override = loadGrayOverride();
        if (!isGrayEnabled(gray, override)) {
            return request;
        }
        if (!isGrayMatched(gray, request)) {
            return request;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (request.context() != null) {
            merged.putAll(request.context());
        }
        merged.put("grayRelease", true);
        merged.put("grayStrategyVersion", resolveGrayStrategyVersion(gray, override));
        if (resolvePreferAsyncA2a(gray, override)) {
            merged.put("forceAsyncA2a", true);
        }
        Map<String, String> effectivePreferredAgentIds = resolveEffectivePreferredAgentIds(gray, override);
        if (!effectivePreferredAgentIds.isEmpty()) {
            merged.put("preferredAgentIds", Map.copyOf(effectivePreferredAgentIds));
        }
        Map<String, String> effectiveRouteOverrideDomains = resolveEffectiveRouteOverrideDomains(gray, override);
        if (!effectiveRouteOverrideDomains.isEmpty()) {
            merged.put("routeOverrideDomains", Map.copyOf(effectiveRouteOverrideDomains));
        }
        return new SupervisorTaskRequest(
                request.sessionId(),
                request.userId(),
                request.requestId(),
                request.traceId(),
                request.userMessage(),
                merged,
                request.channel(),
                request.stream()
        );
    }

    public Map<String, Object> extractGovernanceMetadata(SupervisorTaskRequest request) {
        return extractGovernanceMetadata(request == null ? null : request.context());
    }

    public Map<String, Object> extractGovernanceMetadata(Map<String, Object> context) {
        if (context == null || !Boolean.TRUE.equals(context.get("grayRelease"))) {
            return Map.of();
        }
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("grayRelease", true);
        Object strategyVersion = context.get("grayStrategyVersion");
        if (strategyVersion != null && StringUtils.hasText(String.valueOf(strategyVersion))) {
            metadata.put("grayStrategyVersion", String.valueOf(strategyVersion));
        }
        Object forceAsyncA2a = context.get("forceAsyncA2a");
        if (forceAsyncA2a != null) {
            metadata.put("forceAsyncA2a", forceAsyncA2a);
        }
        Object preferredAgentIds = context.get("preferredAgentIds");
        if (preferredAgentIds instanceof Map<?, ?> preferredMap && !preferredMap.isEmpty()) {
            metadata.put("preferredAgentIds", preferredMap);
        }
        Object routeOverrideDomains = context.get("routeOverrideDomains");
        if (routeOverrideDomains instanceof Map<?, ?> routeMap && !routeMap.isEmpty()) {
            metadata.put("routeOverrideDomains", routeMap);
        }
        return Map.copyOf(metadata);
    }

    public String resolvePreferredAgentOverride(String domain, Map<String, Object> context) {
        return resolvePreferredAgentId(domain, context);
    }

    public String resolveRouteOverrideDomain(Map<String, Object> context, String routeKey) {
        if (context == null || !StringUtils.hasText(routeKey)) {
            return null;
        }
        Object overrides = context.get("routeOverrideDomains");
        if (!(overrides instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = map.get(routeKey);
        return value == null || !StringUtils.hasText(String.valueOf(value)) ? null : String.valueOf(value);
    }

    public SupervisorGovernanceView viewGovernance() {
        DistributedAgentProperties.GrayReleaseProperties gray = distributedAgentProperties.getGrayRelease();
        PersistentGrayOverride override = loadGrayOverride();
        Map<String, Integer> rateLimitOverrides = loadRateLimitOverrides();
        Map<String, Object> rateLimit = new LinkedHashMap<>();
        rateLimit.put("enabled", distributedAgentProperties.getRateLimit().isEnabled());
        rateLimit.put("provider", distributedAgentProperties.getRateLimit().getProvider());
        rateLimit.put("windowSeconds", distributedAgentProperties.getRateLimit().getWindowSeconds());
        rateLimit.put("overrides", Map.copyOf(rateLimitOverrides));

        Map<String, Object> grayRelease = new LinkedHashMap<>();
        grayRelease.put("enabled", isGrayEnabled(gray, override));
        grayRelease.put("strategyVersion", resolveGrayStrategyVersion(gray, override));
        grayRelease.put("preferAsyncA2a", resolvePreferAsyncA2a(gray, override));
        grayRelease.put("enabledOverride", override.enabledOverride());
        grayRelease.put("strategyVersionOverride", override.strategyVersionOverride());
        grayRelease.put("preferAsyncOverride", override.preferAsyncOverride());

        return new SupervisorGovernanceView(
                Map.copyOf(rateLimit),
                Map.copyOf(grayRelease),
                sessionEventAuditService.summarize()
        );
    }

    public SupervisorGovernanceView overrideRateLimit(GovernanceRateLimitOverrideRequest request) {
        if (request != null && StringUtils.hasText(request.entryType()) && request.perWindow() != null && request.perWindow() > 0) {
            AgentGovernanceOverrideEntity entity = findOverride(OVERRIDE_TYPE_RATE_LIMIT, request.entryType());
            if (entity == null) {
                entity = new AgentGovernanceOverrideEntity();
                entity.setOverrideType(OVERRIDE_TYPE_RATE_LIMIT);
                entity.setOverrideKey(request.entryType());
            }
            entity.setPayloadJson(writeJson(Map.of("perWindow", request.perWindow())));
            entity.setActive(1);
            saveOverride(entity);
        }
        return viewGovernance();
    }

    public SupervisorGovernanceView clearRateLimitOverride(String entryType) {
        if (StringUtils.hasText(entryType)) {
            deleteOverride(OVERRIDE_TYPE_RATE_LIMIT, entryType);
        } else {
            governanceOverrideMapper.delete(new LambdaQueryWrapper<AgentGovernanceOverrideEntity>()
                    .eq(AgentGovernanceOverrideEntity::getOverrideType, OVERRIDE_TYPE_RATE_LIMIT));
        }
        return viewGovernance();
    }

    public SupervisorGovernanceView overrideGrayRelease(GovernanceGrayReleaseOverrideRequest request) {
        if (request != null) {
            AgentGovernanceOverrideEntity entity = findOverride(OVERRIDE_TYPE_GRAY_RELEASE, OVERRIDE_KEY_GRAY_GLOBAL);
            if (entity == null) {
                entity = new AgentGovernanceOverrideEntity();
                entity.setOverrideType(OVERRIDE_TYPE_GRAY_RELEASE);
                entity.setOverrideKey(OVERRIDE_KEY_GRAY_GLOBAL);
            }
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            if (request.enabled() != null) {
                payload.put("enabled", request.enabled());
            }
            if (StringUtils.hasText(request.strategyVersion())) {
                payload.put("strategyVersion", request.strategyVersion());
            }
            if (request.preferAsyncA2a() != null) {
                payload.put("preferAsyncA2a", request.preferAsyncA2a());
            }
            entity.setPayloadJson(writeJson(payload));
            entity.setActive(1);
            saveOverride(entity);
        }
        return viewGovernance();
    }

    public SupervisorGovernanceView clearGrayReleaseOverride() {
        deleteOverride(OVERRIDE_TYPE_GRAY_RELEASE, OVERRIDE_KEY_GRAY_GLOBAL);
        return viewGovernance();
    }

    private boolean isGrayMatched(DistributedAgentProperties.GrayReleaseProperties gray,
                                  SupervisorTaskRequest request) {
        if (contains(gray.getUserIds(), request.userId())) {
            return true;
        }
        if (contains(gray.getSessionIds(), request.sessionId())) {
            return true;
        }
        String domain = resolveDomain(request);
        return contains(gray.getDomains(), domain);
    }

    private String resolveDomain(SupervisorTaskRequest request) {
        Object value = request.context() == null ? null : request.context().get("domain");
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            return String.valueOf(value);
        }
        String normalized = request.userMessage() == null ? "" : request.userMessage().toLowerCase();
        if (normalized.contains("contract")) {
            return "contract";
        }
        if (normalized.contains("notification")) {
            return "notification";
        }
        if (normalized.contains("settlement")) {
            return "settlement";
        }
        if (normalized.contains("marketing")
                || normalized.contains("publish")
                || normalized.contains("copy")
                || normalized.contains("media")) {
            return "marketing";
        }
        if (normalized.contains("trade") || normalized.contains("risk")) {
            return "trade";
        }
        return "listing";
    }

    private boolean contains(Set<String> values, String candidate) {
        return StringUtils.hasText(candidate) && values.contains(candidate);
    }

    private int resolveLimit(String entryType, DistributedAgentProperties.RateLimitProperties properties) {
        Integer override = loadRateLimitOverrides().get(entryType);
        if (override != null) {
            return override;
        }
        return switch (entryType) {
            case "supervisor.tasks" -> properties.getSupervisorTasksPerWindow();
            case "supervisor.tasks.async" -> properties.getSupervisorAsyncTasksPerWindow();
            case "supervisor.workflows" -> properties.getSupervisorWorkflowsPerWindow();
            case "supervisor.workflows.async" -> properties.getSupervisorAsyncWorkflowsPerWindow();
            default -> properties.getDefaultPerWindow();
        };
    }

    private String resolveIdentity(SupervisorTaskRequest request) {
        if (StringUtils.hasText(request.userId())) {
            return "user:" + request.userId();
        }
        if (StringUtils.hasText(request.sessionId())) {
            return "session:" + request.sessionId();
        }
        return "anonymous";
    }

    private String resolvePreferredAgentId(String domain, Map<String, Object> context) {
        if (context == null || !StringUtils.hasText(domain)) {
            return null;
        }
        Object preferred = context.get("preferredAgentIds");
        if (!(preferred instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = map.get(domain);
        return value == null || !StringUtils.hasText(String.valueOf(value)) ? null : String.valueOf(value);
    }

    private Map<String, String> resolveEffectivePreferredAgentIds(DistributedAgentProperties.GrayReleaseProperties gray,
                                                                  PersistentGrayOverride override) {
        Map<String, String> resolved = new LinkedHashMap<>(gray.getPreferredAgentIds());
        String strategyVersion = resolveGrayStrategyVersion(gray, override);
        if (!StringUtils.hasText(strategyVersion)) {
            return resolved;
        }
        Map<String, String> versioned = gray.getVersionedPreferredAgentIds().get(strategyVersion);
        if (versioned != null && !versioned.isEmpty()) {
            resolved.putAll(versioned);
        }
        return resolved;
    }

    private Map<String, String> resolveEffectiveRouteOverrideDomains(DistributedAgentProperties.GrayReleaseProperties gray,
                                                                     PersistentGrayOverride override) {
        Map<String, String> resolved = new LinkedHashMap<>(gray.getRouteOverrideDomains());
        String strategyVersion = resolveGrayStrategyVersion(gray, override);
        if (!StringUtils.hasText(strategyVersion)) {
            return resolved;
        }
        Map<String, String> versioned = gray.getVersionedRouteOverrideDomains().get(strategyVersion);
        if (versioned != null && !versioned.isEmpty()) {
            resolved.putAll(versioned);
        }
        return resolved;
    }

    private boolean isGrayEnabled(DistributedAgentProperties.GrayReleaseProperties gray, PersistentGrayOverride override) {
        return override.enabledOverride() != null ? override.enabledOverride() : gray.isEnabled();
    }

    private String resolveGrayStrategyVersion(DistributedAgentProperties.GrayReleaseProperties gray, PersistentGrayOverride override) {
        return StringUtils.hasText(override.strategyVersionOverride()) ? override.strategyVersionOverride() : gray.getStrategyVersion();
    }

    private boolean resolvePreferAsyncA2a(DistributedAgentProperties.GrayReleaseProperties gray, PersistentGrayOverride override) {
        return override.preferAsyncOverride() != null ? override.preferAsyncOverride() : gray.isPreferAsyncA2a();
    }

    private Map<String, Integer> loadRateLimitOverrides() {
        List<AgentGovernanceOverrideEntity> entities = governanceOverrideMapper.selectList(
                new LambdaQueryWrapper<AgentGovernanceOverrideEntity>()
                        .eq(AgentGovernanceOverrideEntity::getOverrideType, OVERRIDE_TYPE_RATE_LIMIT)
                        .eq(AgentGovernanceOverrideEntity::getActive, 1)
        );
        LinkedHashMap<String, Integer> overrides = new LinkedHashMap<>();
        for (AgentGovernanceOverrideEntity entity : entities) {
            Integer value = readInteger(entity.getPayloadJson(), "perWindow");
            if (value != null && value > 0) {
                overrides.put(entity.getOverrideKey(), value);
            }
        }
        return overrides;
    }

    private PersistentGrayOverride loadGrayOverride() {
        AgentGovernanceOverrideEntity entity = findOverride(OVERRIDE_TYPE_GRAY_RELEASE, OVERRIDE_KEY_GRAY_GLOBAL);
        if (entity == null || entity.getActive() == null || entity.getActive() != 1) {
            return new PersistentGrayOverride(null, null, null);
        }
        Map<String, Object> payload = readJson(entity.getPayloadJson());
        return new PersistentGrayOverride(
                readBoolean(payload, "enabled"),
                readString(payload, "strategyVersion"),
                readBoolean(payload, "preferAsyncA2a")
        );
    }

    private AgentGovernanceOverrideEntity findOverride(String type, String key) {
        return governanceOverrideMapper.selectOne(
                new LambdaQueryWrapper<AgentGovernanceOverrideEntity>()
                        .eq(AgentGovernanceOverrideEntity::getOverrideType, type)
                        .eq(AgentGovernanceOverrideEntity::getOverrideKey, key)
                        .last("limit 1")
        );
    }

    private void saveOverride(AgentGovernanceOverrideEntity entity) {
        if (entity.getId() == null) {
            governanceOverrideMapper.insert(entity);
            return;
        }
        governanceOverrideMapper.updateById(entity);
    }

    private void deleteOverride(String type, String key) {
        governanceOverrideMapper.delete(new LambdaQueryWrapper<AgentGovernanceOverrideEntity>()
                .eq(AgentGovernanceOverrideEntity::getOverrideType, type)
                .eq(AgentGovernanceOverrideEntity::getOverrideKey, key));
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize governance override", exception);
        }
    }

    private Map<String, Object> readJson(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private Integer readInteger(String json, String key) {
        Object value = readJson(json).get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            return Integer.parseInt(String.valueOf(value));
        }
        return null;
    }

    private String readString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null || !StringUtils.hasText(String.valueOf(value)) ? null : String.valueOf(value);
    }

    private Boolean readBoolean(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        return null;
    }

    private record PersistentGrayOverride(Boolean enabledOverride,
                                          String strategyVersionOverride,
                                          Boolean preferAsyncOverride) {
    }
}
