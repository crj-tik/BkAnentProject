package com.bkanent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * DistributedAgentProperties 分布式 Agent 静态注册配置。
 */
@ConfigurationProperties(prefix = "agent.distributed")
public class DistributedAgentProperties {

    private String supervisorAgentId = "supervisor-agent";
    private boolean discoveryEnabled = true;
    private boolean httpCardFallbackEnabled = true;
    private String agentCardPath = "/.well-known/agent.json";
    private long refreshIntervalSeconds = 30;
    private final CatalogProperties catalog = new CatalogProperties();
    private final RateLimitProperties rateLimit = new RateLimitProperties();
    private final GrayReleaseProperties grayRelease = new GrayReleaseProperties();
    private final EventAuditProperties eventAudit = new EventAuditProperties();
    private final StreamingProperties streaming = new StreamingProperties();
    private final AsyncRuntimeProperties asyncRuntime = new AsyncRuntimeProperties();
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
    public boolean isHttpCardFallbackEnabled() { return httpCardFallbackEnabled; }
    public void setHttpCardFallbackEnabled(boolean value) { httpCardFallbackEnabled = value; }

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

    public CatalogProperties getCatalog() {
        return catalog;
    }

    public GrayReleaseProperties getGrayRelease() {
        return grayRelease;
    }

    public EventAuditProperties getEventAudit() {
        return eventAudit;
    }

    public StreamingProperties getStreaming() {
        return streaming;
    }

    public AsyncRuntimeProperties getAsyncRuntime() {
        return asyncRuntime;
    }

    public Map<String, AgentRegistration> getAgents() {
        return agents;
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
        private static final int MAX_LEASE_OWNER_LENGTH = 128;
        private static final int LEASE_OWNER_SUFFIX_LENGTH = 1 + 36 + 1 + 36;

        private boolean enabled = true;
        private long dispatchIntervalMs = 1000L;
        private int dispatchBatchSize = 10;
        private int maxConcurrency = 4;
        private int queueCapacity = 32;
        private long leaseTimeoutSeconds = 300L;
        private int maxAttempts = 3;
        private long retryDelaySeconds = 5L;
        private long childTaskSubmitRequestTimeoutMs = 15_000L;
        private long childTaskTimeoutMs = 1_800_000L;
        private long childTaskPollRequestTimeoutMs = 15_000L;
        private long childTaskInitialPollIntervalMs = 1_000L;
        private long childTaskMaxPollIntervalMs = 10_000L;
        private double childTaskPollBackoffMultiplier = 1.5d;
        private int childTaskPollJitterPercent = 20;
        private String workerId = "agent-worker";
        private final String workerInstanceId = UUID.randomUUID().toString();

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

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public long getLeaseTimeoutSeconds() {
            return leaseTimeoutSeconds;
        }

        public void setLeaseTimeoutSeconds(long leaseTimeoutSeconds) {
            this.leaseTimeoutSeconds = leaseTimeoutSeconds;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getRetryDelaySeconds() {
            return retryDelaySeconds;
        }

        public void setRetryDelaySeconds(long retryDelaySeconds) {
            this.retryDelaySeconds = retryDelaySeconds;
        }

        public long getChildTaskSubmitRequestTimeoutMs() {
            return childTaskSubmitRequestTimeoutMs;
        }

        public void setChildTaskSubmitRequestTimeoutMs(long childTaskSubmitRequestTimeoutMs) {
            this.childTaskSubmitRequestTimeoutMs = childTaskSubmitRequestTimeoutMs;
        }

        public long getChildTaskTimeoutMs() {
            return childTaskTimeoutMs;
        }

        public void setChildTaskTimeoutMs(long childTaskTimeoutMs) {
            this.childTaskTimeoutMs = childTaskTimeoutMs;
        }

        public long getChildTaskPollRequestTimeoutMs() {
            return childTaskPollRequestTimeoutMs;
        }

        public void setChildTaskPollRequestTimeoutMs(long childTaskPollRequestTimeoutMs) {
            this.childTaskPollRequestTimeoutMs = childTaskPollRequestTimeoutMs;
        }

        public long getChildTaskInitialPollIntervalMs() {
            return childTaskInitialPollIntervalMs;
        }

        public void setChildTaskInitialPollIntervalMs(long childTaskInitialPollIntervalMs) {
            this.childTaskInitialPollIntervalMs = childTaskInitialPollIntervalMs;
        }

        public long getChildTaskMaxPollIntervalMs() {
            return childTaskMaxPollIntervalMs;
        }

        public void setChildTaskMaxPollIntervalMs(long childTaskMaxPollIntervalMs) {
            this.childTaskMaxPollIntervalMs = childTaskMaxPollIntervalMs;
        }

        public double getChildTaskPollBackoffMultiplier() {
            return childTaskPollBackoffMultiplier;
        }

        public void setChildTaskPollBackoffMultiplier(double childTaskPollBackoffMultiplier) {
            this.childTaskPollBackoffMultiplier = childTaskPollBackoffMultiplier;
        }

        public int getChildTaskPollJitterPercent() {
            return childTaskPollJitterPercent;
        }

        public void setChildTaskPollJitterPercent(int childTaskPollJitterPercent) {
            this.childTaskPollJitterPercent = childTaskPollJitterPercent;
        }

        public String getWorkerId() {
            return workerId;
        }

        /**
         * Returns a process-unique lease owner even when every instance uses
         * the same configured worker-id prefix.
         */
        public String getInstanceWorkerId() {
            return normalizedWorkerIdPrefix() + "-" + workerInstanceId;
        }

        public String createLeaseOwner() {
            return getInstanceWorkerId() + "-" + UUID.randomUUID();
        }

        private String normalizedWorkerIdPrefix() {
            String prefix = workerId == null || workerId.isBlank() ? "agent-worker" : workerId.trim();
            int maxPrefixLength = MAX_LEASE_OWNER_LENGTH - LEASE_OWNER_SUFFIX_LENGTH;
            return prefix.length() > maxPrefixLength ? prefix.substring(0, maxPrefixLength) : prefix;
        }

        public void setWorkerId(String workerId) {
            this.workerId = workerId;
        }
    }

    public static class StreamingProperties {
        private boolean enabled = true;
        private int maxDeltaChars = 4096;
        private int maxDeltaEventsPerSecond = 50;
        private int maxMetadataChars = 4096;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxDeltaChars() {
            return maxDeltaChars;
        }

        public void setMaxDeltaChars(int maxDeltaChars) {
            this.maxDeltaChars = maxDeltaChars;
        }

        public int getMaxDeltaEventsPerSecond() {
            return maxDeltaEventsPerSecond;
        }

        public void setMaxDeltaEventsPerSecond(int maxDeltaEventsPerSecond) {
            this.maxDeltaEventsPerSecond = maxDeltaEventsPerSecond;
        }

        public int getMaxMetadataChars() {
            return maxMetadataChars;
        }

        public void setMaxMetadataChars(int maxMetadataChars) {
            this.maxMetadataChars = maxMetadataChars;
        }
    }

    public static class AgentRegistration {
        private String agentId;
        private String name;
        private String description;
        private String version = "1.0.0";
        private String runtimeProvider = "official";
        private String serviceId;
        private String baseUrl;
        private String agentCardPath;
        private String a2aPath = "/a2a";
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
