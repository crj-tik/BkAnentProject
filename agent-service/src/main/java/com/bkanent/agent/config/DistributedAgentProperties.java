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

    public PlanningProperties getPlanning() {
        return planning;
    }

    public Map<String, AgentRegistration> getAgents() {
        return agents;
    }

    public static class CatalogProperties {
        private boolean strictNacos = true;
        /**
         * 并行图分支槽位容量（编译期拓扑概念）。未被路由的空槽位不执行、不耗资源；
         * 修改后需重启重建图。必须不小于 planning.max-parallel-domains。
         */
        private int branchCapacity = 16;
        /**
         * 冷启动专用兜底领域词表：仅在 Agent 注册表首次成功刷新（返回至少一个 Agent）
         * 之前生效，之后由注册表动态词表完全接管，本表不再参与合并。禁止向本表追加新域。
         */
        private List<String> coldStartFallbackDomains = new ArrayList<>(List.of(
                "listing", "marketing", "media", "trade", "contract", "settlement", "notification"
        ));
        /**
         * 存量领域的默认 intent 种子表，仅用于保持现状行为兼容。新领域必须经由注册元数据
         * agent-default-intent 或 Agent Card supportedSkills 声明，禁止向本表追加新域。
         */
        private Map<String, String> defaultIntents = seededDefaultIntents();
        /**
         * nextHint 改写表：先改写再按 '.' 前缀切出目标领域。
         */
        private Map<String, String> hintRewrites = new LinkedHashMap<>(Map.of(
                "settlement.batch", "settlement.prepare"
        ));
        private final RuleRoutingProperties ruleRouting = new RuleRoutingProperties();

        private static Map<String, String> seededDefaultIntents() {
            Map<String, String> seeds = new LinkedHashMap<>();
            seeds.put("listing", "listing.search");
            seeds.put("marketing", "marketing.generate_copy");
            seeds.put("media", "media.generate_video_task");
            seeds.put("trade", "trade.feasibility_analysis");
            seeds.put("contract", "contract.risk_review");
            seeds.put("notification", "notification.send");
            seeds.put("settlement", "settlement.prepare");
            return seeds;
        }

        public boolean isStrictNacos() {
            return strictNacos;
        }

        public void setStrictNacos(boolean strictNacos) {
            this.strictNacos = strictNacos;
        }

        public int getBranchCapacity() {
            return branchCapacity;
        }

        public void setBranchCapacity(int branchCapacity) {
            this.branchCapacity = branchCapacity;
        }

        public List<String> getColdStartFallbackDomains() {
            return coldStartFallbackDomains;
        }

        public void setColdStartFallbackDomains(List<String> coldStartFallbackDomains) {
            this.coldStartFallbackDomains = coldStartFallbackDomains;
        }

        public Map<String, String> getDefaultIntents() {
            return defaultIntents;
        }

        public void setDefaultIntents(Map<String, String> defaultIntents) {
            this.defaultIntents = defaultIntents;
        }

        public Map<String, String> getHintRewrites() {
            return hintRewrites;
        }

        public void setHintRewrites(Map<String, String> hintRewrites) {
            this.hintRewrites = hintRewrites;
        }

        public RuleRoutingProperties getRuleRouting() {
            return ruleRouting;
        }
    }

    public static class RuleRoutingProperties {
        /**
         * 规则路由未命中任何关键词时回退的默认领域。
         */
        private String defaultDomain = "listing";
        /**
         * 规则路由加速层关键词表（按领域分组，按配置顺序匹配）。命中的领域必须是
         * 当前领域目录成员，否则忽略。未来规划：规则路由整体降级为 LLM 规划失败的
         * 兜底，关键词只保留高置信场景，见 docs/supervisor-routing-roadmap.md。
         */
        private Map<String, List<String>> keywords = seededKeywords();

        private static Map<String, List<String>> seededKeywords() {
            Map<String, List<String>> seeds = new LinkedHashMap<>();
            seeds.put("contract", List.of("合同", "签约", "归档", "ocr", "OCR", "contract"));
            seeds.put("notification", List.of("通知", "提醒", "消息", "notification"));
            seeds.put("settlement", List.of("结算", "佣金", "出款", "打款", "settlement"));
            seeds.put("marketing", List.of("文案", "营销", "广告", "推广", "小红书", "抖音"));
            seeds.put("trade", List.of("交易", "成交", "风险", "可行性", "trade"));
            return seeds;
        }
        /**
         * 规则路由并行规则：请求上下文 requireParallel=true 且每个关键词组都至少
         * 命中一个词时，返回配置的并行领域列表。
         */
        private List<ParallelRoutingRule> parallelRules = new ArrayList<>(List.of(
                new ParallelRoutingRule(
                        List.of(
                                List.of("房源", "找房", "小区", "listing", "房子"),
                                List.of("交易", "成交", "风险", "可行性", "trade")
                        ),
                        List.of("listing", "trade")
                )
        ));

        public String getDefaultDomain() {
            return defaultDomain;
        }

        public void setDefaultDomain(String defaultDomain) {
            this.defaultDomain = defaultDomain;
        }

        public Map<String, List<String>> getKeywords() {
            return keywords;
        }

        public void setKeywords(Map<String, List<String>> keywords) {
            this.keywords = keywords;
        }

        public List<ParallelRoutingRule> getParallelRules() {
            return parallelRules;
        }

        public void setParallelRules(List<ParallelRoutingRule> parallelRules) {
            this.parallelRules = parallelRules;
        }
    }

    public static class ParallelRoutingRule {
        private List<List<String>> keywordGroups = new ArrayList<>();
        private List<String> domains = new ArrayList<>();

        public ParallelRoutingRule() {
        }

        public ParallelRoutingRule(List<List<String>> keywordGroups, List<String> domains) {
            this.keywordGroups = keywordGroups;
            this.domains = domains;
        }

        public List<List<String>> getKeywordGroups() {
            return keywordGroups;
        }

        public void setKeywordGroups(List<List<String>> keywordGroups) {
            this.keywordGroups = keywordGroups;
        }

        public List<String> getDomains() {
            return domains;
        }

        public void setDomains(List<String> domains) {
            this.domains = domains;
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

    public static class PlanningProperties {
        private boolean llmEnabled = false;
        private boolean allowFallback = true;
        private String strategy = "rule-first";
        /**
         * 运行期单次计划的并行扇出上限。必须不大于 catalog.branch-capacity，
         * 否则启动失败。
         */
        private int maxParallelDomains = 7;

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

        public int getMaxParallelDomains() {
            return maxParallelDomains;
        }

        public void setMaxParallelDomains(int maxParallelDomains) {
            this.maxParallelDomains = maxParallelDomains;
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
        private String runtimeProvider = "official";
        private String serviceId;
        private String baseUrl;
        private String agentCardPath;
        private String a2aPath = "/a2a";
        private List<String> supportedSkills = new ArrayList<>();
        private List<String> supportedDomains = new ArrayList<>();
        /**
         * 显式声明该 Agent 的默认 intent（等价于 Nacos 元数据 agent-default-intent），
         * 优先级高于 catalog.default-intents 种子表与 Agent Card 技能派生。
         */
        private String defaultIntent;
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

        public String getDefaultIntent() {
            return defaultIntent;
        }

        public void setDefaultIntent(String defaultIntent) {
            this.defaultIntent = defaultIntent;
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
