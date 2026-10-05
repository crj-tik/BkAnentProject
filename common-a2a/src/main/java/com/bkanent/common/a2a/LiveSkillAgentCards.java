package com.bkanent.common.a2a;

import com.alibaba.cloud.ai.a2a.registry.nacos.service.NacosA2aOperationService;
import com.bkanent.common.agent.SkillPublication;
import com.bkanent.common.skill.core.SkillRegistry;
import io.a2a.spec.AgentCard;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** LR-28: HTTP switches locally; Nacos republishes the latest snapshot with retries. */
public class LiveSkillAgentCards implements SmartInitializingSingleton, DisposableBean {
    private final SkillRegistry registry;
    private final ObjectProvider<AgentCard> cards;
    private final ObjectProvider<NacosA2aOperationService> nacos;
    private final Map<String, AgentCard> current = new ConcurrentHashMap<>();
    private final Map<String, AgentCard> pending = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean publicationQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private final ScheduledExecutorService publisher = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "skill-card-publisher"); thread.setDaemon(true); return thread;
    });
    private AutoCloseable subscription;

    public LiveSkillAgentCards(SkillRegistry registry, ObjectProvider<AgentCard> cards,
                              ObjectProvider<NacosA2aOperationService> nacos) {
        this.registry = registry; this.cards = cards; this.nacos = nacos;
    }

    @Override public void afterSingletonsInstantiated() {
        synchronized (registry) {
            cards.orderedStream().filter(card -> owner(card) != null).forEach(card -> current.put(card.name(), card));
            pending.putAll(current);
            subscription = registry.onReload(this::refresh);
            refresh();
        }
        publisher.scheduleWithFixedDelay(this::publishPending, 1, 5, TimeUnit.SECONDS);
    }

    public AgentCard currentCard(AgentCard original) { return current.getOrDefault(original.name(), original); }

    void refresh() {
        current.forEach((name, card) -> {
            AgentCard updated = SkillAgentCardPublisher.publish(card, registry, owner(card));
            if (updated.equals(card)) return;
            current.put(name, updated);
            pending.put(name, updated);
        });
        if (!pending.isEmpty() && publicationQueued.compareAndSet(false, true)) {
            publisher.execute(() -> {
                try { publishPending(); }
                finally { publicationQueued.set(false); }
            });
        }
    }

    synchronized void publishPending() {
        try {
            NacosA2aOperationService service = nacos.getIfAvailable();
            if (service == null) { pending.clear(); return; }
            pending.forEach((name, card) -> {
                try {
                    if (service instanceof SkillAwareNacosOperationService compatible) compatible.republishAgent(card);
                    else service.registerAgent(card);
                    pending.remove(name, card);
                }
                catch (RuntimeException exception) {
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Skill Card publication pending for {}; retrying", name);
                }
            });
        } catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Skill Card publisher unavailable; retrying");
        }
    }

    private String owner(AgentCard card) {
        if (card.capabilities() == null || card.capabilities().extensions() == null) return null;
        return card.capabilities().extensions().stream()
                .filter(extension -> SkillPublication.EXTENSION_URI.equals(extension.uri()))
                .map(extension -> extension.params() == null ? null : extension.params().get("owner"))
                .filter(String.class::isInstance).map(String.class::cast).findFirst().orElse(null);
    }

    @Override public void destroy() throws Exception {
        if (subscription != null) subscription.close();
        publisher.shutdownNow();
    }
}
