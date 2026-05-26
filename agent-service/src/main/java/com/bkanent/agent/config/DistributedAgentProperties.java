package com.bkanent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DistributedAgentProperties 分布式 Agent 静态注册配置。
 */
@ConfigurationProperties(prefix = "agent.distributed")
public class DistributedAgentProperties {

    private String supervisorAgentId = "supervisor-agent";
    private boolean discoveryEnabled = true;
    private String agentCardPath = "/.well-known/agent.json";
    private long refreshIntervalSeconds = 30;
    private final A2aProperties a2a = new A2aProperties();
    private final CatalogProperties catalog = new CatalogProperties();
    private final RateLimitProperties rateLimit = new RateLimitProperties();
    private final GrayReleaseProperties grayRelease = new GrayReleaseProperties();
    private final EventAuditProperties eventAudit = new EventAuditProperties();
    private final AsyncRuntimeProperties asyncRuntime = new AsyncRuntimeProperties();
    private final PlanningProperties planning = new PlanningProperties();
    private final Map<String, AgentRegistration> agents = new LinkedHashMap<>();

    public String getSupervisorAgentId() {
        return supervisorAgentId;
    }

    public void setSupervisorAgentId(String supervisorAgentId) {
        this.supervisorAgentId = supervisorAgentId;
    }

    public boolean isDiscoveryEnabled() {
        return discoveryEnabled;
    }

    public void setDiscoveryEnabled(boolean discoveryEnabled) {
        this.discoveryEnabled = discoveryEnabled;
    }

    public String getAgentCardPath() {
        return agentCardPath;
    }

    public void setAgentCardPath(String agentCardPath) {
        this.agentCardPath = agentCardPath;
    }

    public long getRefreshIntervalSeconds() {
        return refreshIntervalSeconds;
    }

    public void setRefreshIntervalSeconds(long refreshIntervalSeconds) {
        this.refreshIntervalSeconds = refreshIntervalSeconds;
    }

    public RateLimitProperties getRateLimit() {
        return rateLimit;
    }

    public A2aProperties getA2a() {
        return a2a;
    }

    public CatalogProperties getCatalog() {
        return catalog;
    }

    public GrayReleaseProperties getGrayRelease() {
        return grayRelease;
    }

    public EventAuditProperties getEventAudit() {
        return eventAudit;
    }

    public AsyncRuntimeProperties getAsyncRuntime() {
        return asyncRuntime;
    }

    public PlanningProperties getPlanning() {
        return planning;
    }

    public Map<String, AgentRegistration> getAgents() {
        return agents;
    }

    public static class A2aProperties {
        private String discoveryProvider = "custom";

        public String getDiscoveryProvider() {
            return discoveryProvider;
        }

        public void setDiscoveryProvider(String discoveryProvider) {
            this.discoveryProvider = discoveryProvider;
        }
    }

    public static class CatalogProperties {
        private boolean strictNacos = true;

        public boolean isStrictNacos() {
            return strictNacos;
        }

        public void setStrictNacos(boolean strictNacos) {
            this.strictNacos = strictNacos;
        }
    }

    public static class RateLimitProperties {
        private boolean enabled = true;
        private String provider = "memory";
        private long windowSeconds = 60;
        private int defaultPerWindow = 60;
        private int supervisorTasksPerWindow = 60;
        private int supervisorAsyncTasksPerWindow = 40;
        private int supervisorWorkflowsPerWindow = 30;
        private int supervisorAsyncWorkflowsPerWindow = 20;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public long getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(long windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public int getDefaultPerWindow() {
            return defaultPerWindow;
        }

        public void setDefaultPerWindow(int defaultPerWindow) {
            this.defaultPerWindow = defaultPerWindow;
        }

        public int getSupervisorTasksPerWindow() {
            return supervisorTasksPerWindow;
        }

        public void setSupervisorTasksPerWindow(int supervisorTasksPerWindow) {
            this.supervisorTasksPerWindow = supervisorTasksPerWindow;
        }

        public int getSupervisorAsyncTasksPerWindow() {
            return supervisorAsyncTasksPerWindow;
        }

        public void setSupervisorAsyncTasksPerWindow(int supervisorAsyncTasksPerWindow) {
            this.supervisorAsyncTasksPerWindow = supervisorAsyncTasksPerWindow;
        }

        public int getSupervisorWorkflowsPerWindow() {
            return supervisorWorkflowsPerWindow;
        }

        public void setSupervisorWorkflowsPerWindow(int supervisorWorkflowsPerWindow) {
            this.supervisorWorkflowsPerWindow = supervisorWorkflowsPerWindow;
        }

        public int getSupervisorAsyncWorkflowsPerWindow() {
            return supervisorAsyncWorkflowsPerWindow;
        }

        public void setSupervisorAsyncWorkflowsPerWindow(int supervisorAsyncWorkflowsPerWindow) {
            this.supervisorAsyncWorkflowsPerWindow = supervisorAsyncWorkflowsPerWindow;
        }
    }

    public static class GrayReleaseProperties {
        private boolean enabled;
        private String strategyVersion = "v2";
        private boolean preferAsyncA2a;
        private Set<String> userIds = new LinkedHashSet<>();
        private Set<String> sessionIds = new LinkedHashSet<>();
        private Set<String> domains = new LinkedHashSet<>();
        private Map<String, String> preferredAgentIds = new LinkedHashMap<>();
        private Map<String, String> routeOverrideDomains = new LinkedHashMap<>();
        private Map<String, Map<String, String>> versionedPreferredAgentIds = new LinkedHashMap<>();
        private Map<String, Map<String, String>> versionedRouteOverrideDomains = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getStrategyVersion() {
            return strategyVersion;
        }

        public void setStrategyVersion(String strategyVersion) {
            this.strategyVersion = strategyVersion;
        }

        public boolean isPreferAsyncA2a() {
            return preferAsyncA2a;
        }

        public void setPreferAsyncA2a(boolean preferAsyncA2a) {
            this.preferAsyncA2a = preferAsyncA2a;
        }

        public Set<String> getUserIds() {
            return userIds;
        }

        public void setUserIds(Set<String> userIds) {
            this.userIds = userIds;
        }

        public Set<String> getSessionIds() {
            return sessionIds;
        }

        public void setSessionIds(Set<String> sessionIds) {
            this.sessionIds = sessionIds;
        }

        public Set<String> getDomains() {
            return domains;
        }

        public void setDomains(Set<String> domains) {
            this.domains = domains;
        }

        public Map<String, String> getPreferredAgentIds() {
            return preferredAgentIds;
        }

        public void setPreferredAgentIds(Map<String, String> preferredAgentIds) {
            this.preferredAgentIds = preferredAgentIds;
        }

        public Map<String, String> getRouteOverrideDomains() {
            return routeOverrideDomains;
        }

        public void setRouteOverrideDomains(Map<String, String> routeOverrideDomains) {
            this.routeOverrideDomains = routeOverrideDomains;
        }

        public Map<String, Map<String, String>> getVersionedPreferredAgentIds() {
            return versionedPreferredAgentIds;
        }

        public void setVersionedPreferredAgentIds(Map<String, Map<String, String>> versionedPreferredAgentIds) {
            this.versionedPreferredAgentIds = versionedPreferredAgentIds;
        }

        public Map<String, Map<String, String>> getVersionedRouteOverrideDomains() {
            return versionedRouteOverrideDomains;
        }

        public void setVersionedRouteOverrideDomains(Map<String, Map<String, String>> versionedRouteOverrideDomains) {
            this.versionedRouteOverrideDomains = versionedRouteOverrideDomains;
        }
    }

    public static class EventAuditProperties {
        private int maxActiveEvents = 5000;
        private boolean archiveEnabled = true;
        private int maxArchivedEvents = 10000;
        private long retentionSeconds = 86400;

        public int getMaxActiveEvents() {
            return maxActiveEvents;
        }

        public void setMaxActiveEvents(int maxActiveEvents) {
            this.maxActiveEvents = maxActiveEvents;
        }

        public boolean isArchiveEnabled() {
            return archiveEnabled;
        }

        public void setArchiveEnabled(boolean archiveEnabled) {
            this.archiveEnabled = archiveEnabled;
        }

        public int getMaxArchivedEvents() {
            return maxArchivedEvents;
        }

        public void setMaxArchivedEvents(int maxArchivedEvents) {
            this.maxArchivedEvents = maxArchivedEvents;
        }

        public long getRetentionSeconds() {
            return retentionSeconds;
        }

        public void setRetentionSeconds(long retentionSeconds) {
            this.retentionSeconds = retentionSeconds;
        }
    }

    public static class AsyncRuntimeProperties {
        private boolean enabled = true;
        private long dispatchIntervalMs = 1000L;
        private int dispatchBatchSize = 10;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getDispatchIntervalMs() {
            return dispatchIntervalMs;
        }

        public void setDispatchIntervalMs(long dispatchIntervalMs) {
            this.dispatchIntervalMs = dispatchIntervalMs;
        }

        public int getDispatchBatchSize() {
            return dispatchBatchSize;
        }

        public void setDispatchBatchSize(int dispatchBatchSize) {
            this.dispatchBatchSize = dispatchBatchSize;
        }
    }

    public static class PlanningProperties {
        private boolean llmEnabled = false;
        private boolean allowFallback = true;
        private String strategy = "rule-first";

        public boolean isLlmEnabled() {
            return llmEnabled;
        }

        public void setLlmEnabled(boolean llmEnabled) {
            this.llmEnabled = llmEnabled;
        }

        public boolean isAllowFallback() {
            return allowFallback;
        }

        public void setAllowFallback(boolean allowFallback) {
            this.allowFallback = allowFallback;
        }

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }
    }

    public static class AgentRegistration {
        private String agentId;
        private String name;
        private String description;
        private String version = "1.0.0";
        private String runtimeProvider = "auto";
        private String officialPayloadMode = "auto";
        private String serviceId;
        private String baseUrl;
        private String agentCardPath;
        private String a2aPath = "/a2a";
        private String a2aTaskCreatePath = "/a2a";
        private String a2aTaskStatusPath = "/a2a";
        private String a2aTaskStreamPath = "/a2a";
        private List<String> supportedSkills = new ArrayList<>();
        private List<String> supportedDomains = new ArrayList<>();
        private boolean supportsStreaming;
        private boolean supportsAsyncTask;
        private List<String> inputModes = new ArrayList<>(List.of("text"));
        private List<String> outputModes = new ArrayList<>(List.of("text", "json"));

        public String getAgentId() {
            return agentId;
        }

        public void setAgentId(String agentId) {
            this.agentId = agentId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getRuntimeProvider() {
            return runtimeProvider;
        }

        public void setRuntimeProvider(String runtimeProvider) {
            this.runtimeProvider = runtimeProvider;
        }

        public String getOfficialPayloadMode() {
            return officialPayloadMode;
        }

        public void setOfficialPayloadMode(String officialPayloadMode) {
            this.officialPayloadMode = officialPayloadMode;
        }

        public String getServiceId() {
            return serviceId;
        }

        public void setServiceId(String serviceId) {
            this.serviceId = serviceId;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getAgentCardPath() {
            return agentCardPath;
        }

        public void setAgentCardPath(String agentCardPath) {
            this.agentCardPath = agentCardPath;
        }

        public String getA2aPath() {
            return a2aPath;
        }

        public void setA2aPath(String a2aPath) {
            this.a2aPath = a2aPath;
        }

        public String getA2aTaskCreatePath() {
            return a2aTaskCreatePath;
        }

        public void setA2aTaskCreatePath(String a2aTaskCreatePath) {
            this.a2aTaskCreatePath = a2aTaskCreatePath;
        }

        public String getA2aTaskStatusPath() {
            return a2aTaskStatusPath;
        }

        public void setA2aTaskStatusPath(String a2aTaskStatusPath) {
            this.a2aTaskStatusPath = a2aTaskStatusPath;
        }

        public String getA2aTaskStreamPath() {
            return a2aTaskStreamPath;
        }

        public void setA2aTaskStreamPath(String a2aTaskStreamPath) {
            this.a2aTaskStreamPath = a2aTaskStreamPath;
        }

        public List<String> getSupportedSkills() {
            return supportedSkills;
        }

        public void setSupportedSkills(List<String> supportedSkills) {
            this.supportedSkills = supportedSkills;
        }

        public List<String> getSupportedDomains() {
            return supportedDomains;
        }

        public void setSupportedDomains(List<String> supportedDomains) {
            this.supportedDomains = supportedDomains;
        }

        public boolean isSupportsStreaming() {
            return supportsStreaming;
        }

        public void setSupportsStreaming(boolean supportsStreaming) {
            this.supportsStreaming = supportsStreaming;
        }

        public boolean isSupportsAsyncTask() {
            return supportsAsyncTask;
        }

        public void setSupportsAsyncTask(boolean supportsAsyncTask) {
            this.supportsAsyncTask = supportsAsyncTask;
        }

        public List<String> getInputModes() {
            return inputModes;
        }

        public void setInputModes(List<String> inputModes) {
            this.inputModes = inputModes;
        }

        public List<String> getOutputModes() {
            return outputModes;
        }

        public void setOutputModes(List<String> outputModes) {
            this.outputModes = outputModes;
        }
    }
}
