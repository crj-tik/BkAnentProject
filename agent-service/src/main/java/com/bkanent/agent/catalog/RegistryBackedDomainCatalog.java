package com.bkanent.agent.catalog;

import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.common.agent.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * RegistryBackedDomainCatalog 从 Agent 注册表派生领域目录。
 *
 * <p>冷启动语义：注册表首次返回非空 Agent 列表视为首次成功刷新，此前一律使用
 * 冷启动兜底词表；接管后兜底词表不再参与合并。接管后注册表变空不再回退兜底
 * （注册表即真源），仅告警。兜底生效期间的 WARN 做了节流，避免启动期刷屏。</p>
 */
@Component
public class RegistryBackedDomainCatalog implements DomainCatalog, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(RegistryBackedDomainCatalog.class);
    private static final long WARN_THROTTLE_MS = 60_000L;

    private final AgentRegistry agentRegistry;
    private final DistributedAgentProperties properties;
    private volatile boolean registryReady;
    private volatile long lastWarnAt;

    public RegistryBackedDomainCatalog(AgentRegistry agentRegistry,
                                       DistributedAgentProperties properties) {
        this.agentRegistry = agentRegistry;
        this.properties = properties;
        int maxParallel = properties.getPlanning().getMaxParallelDomains();
        int capacity = properties.getCatalog().getBranchCapacity();
        if (maxParallel > capacity) {
            throw new IllegalStateException(
                    "agent.distributed.planning.max-parallel-domains (" + maxParallel
                            + ") must not exceed agent.distributed.catalog.branch-capacity (" + capacity + ")");
        }
    }

    @Override
    public Set<String> domains() {
        return new LinkedHashSet<>(snapshot().domains());
    }

    @Override
    public String resolveDefaultIntent(String domain) {
        if (!StringUtils.hasText(domain)) {
            return null;
        }
        List<RegisteredAgentDescriptor> descriptors = descriptorsForDomain(domain);
        for (RegisteredAgentDescriptor descriptor : descriptors) {
            String declared = descriptor.metadata().get("agent-default-intent");
            if (StringUtils.hasText(declared)) {
                return declared;
            }
        }
        String configured = properties.getCatalog().getDefaultIntents().get(domain);
        if (StringUtils.hasText(configured)) {
            return configured;
        }
        for (RegisteredAgentDescriptor descriptor : descriptors) {
            List<String> skills = descriptor.agentCard() == null ? null : descriptor.agentCard().supportedSkills();
            if (skills != null && !skills.isEmpty() && StringUtils.hasText(skills.get(0))) {
                return skills.get(0);
            }
        }
        return null;
    }

    @Override
    public String rewriteHint(String nextHint) {
        if (!StringUtils.hasText(nextHint)) {
            return nextHint;
        }
        String rewritten = properties.getCatalog().getHintRewrites().get(nextHint);
        return StringUtils.hasText(rewritten) ? rewritten : nextHint;
    }

    @Override
    public List<AgentCard> cards() {
        return snapshot().cards();
    }

    @Override
    public int branchCapacity() {
        return properties.getCatalog().getBranchCapacity();
    }

    @Override
    public CatalogSnapshot snapshot() {
        List<RegisteredAgentDescriptor> descriptors = agentRegistry.listDescriptors();
        List<AgentCard> cards = new ArrayList<>();
        for (RegisteredAgentDescriptor descriptor : descriptors) {
            if (descriptor != null && descriptor.agentCard() != null) {
                cards.add(descriptor.agentCard());
            }
        }
        if (!cards.isEmpty() && !registryReady) {
            registryReady = true;
            log.info("Domain catalog switched to REGISTRY source; cold-start fallback retired");
        }
        String source;
        List<String> domains;
        if (registryReady) {
            domains = List.copyOf(registryDomains(cards));
            source = SOURCE_REGISTRY;
            if (domains.isEmpty()) {
                warnThrottled("Domain catalog source is REGISTRY but no agent declares any supported domain");
            }
        } else {
            domains = sortedDistinct(properties.getCatalog().getColdStartFallbackDomains());
            source = SOURCE_COLD_START_FALLBACK;
            warnThrottled("Domain catalog is on cold-start fallback vocabulary; "
                    + "agent registry has not returned any agent yet");
        }
        return new CatalogSnapshot(domains, List.copyOf(cards), source);
    }

    @Override
    public void afterSingletonsInstantiated() {
        CatalogSnapshot snapshot = snapshot();
        log.info("Domain catalog initialized: source={}, domains={}, branchCapacity={}, maxParallelDomains={}",
                snapshot.vocabularySource(), snapshot.domains(),
                properties.getCatalog().getBranchCapacity(),
                properties.getPlanning().getMaxParallelDomains());
    }

    private List<RegisteredAgentDescriptor> descriptorsForDomain(String domain) {
        List<RegisteredAgentDescriptor> matched = new ArrayList<>();
        for (RegisteredAgentDescriptor descriptor : agentRegistry.listDescriptors()) {
            List<String> supported = descriptor == null || descriptor.agentCard() == null
                    ? null : descriptor.agentCard().supportedDomains();
            if (supported != null && supported.contains(domain)) {
                matched.add(descriptor);
            }
        }
        return matched;
    }

    private List<String> registryDomains(List<AgentCard> cards) {
        Set<String> domains = new TreeSet<>();
        for (AgentCard card : cards) {
            if (card.supportedDomains() != null) {
                card.supportedDomains().stream()
                        .filter(StringUtils::hasText)
                        .forEach(domains::add);
            }
        }
        return List.copyOf(domains);
    }

    private List<String> sortedDistinct(List<String> values) {
        Set<String> domains = new TreeSet<>();
        values.stream().filter(StringUtils::hasText).forEach(domains::add);
        return List.copyOf(domains);
    }

    private void warnThrottled(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt >= WARN_THROTTLE_MS) {
            lastWarnAt = now;
            log.warn(message);
        }
    }
}
