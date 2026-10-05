package com.bkanent.common.skill.core;

import com.bkanent.common.skill.SkillDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central registry for all loaded skills. Provides indexed lookups by name,
 * domain, and supervisor-mode.
 *
 * <p>Skills are loaded at startup from classpath:skills/**&#47;*.md, optionally merged
 * with an external directory (external overrides classpath on name conflict).
 * The registry can be refreshed at runtime via {@link #reload()}.</p>
 */
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final SkillFileLoader loader;
    private final String externalDir;
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> reloadListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private volatile RegistrySnapshot snapshot = new RegistrySnapshot(List.of(), Map.of(), Map.of());
    private record RegistrySnapshot(List<SkillDefinition> skills, Map<String, SkillDefinition> byName,
                                    Map<String, List<SkillDefinition>> byDomain) {}

    public SkillRegistry(SkillFileLoader loader, String externalDir) {
        this.loader = loader;
        this.externalDir = externalDir;
        reload();
    }

    /**
     * 重新加载全部技能文件。
     * 先加载 classpath 中的 skill.md，再合并外部目录（如果配置了），
     * 外部目录中的同名 skill 会覆盖 classpath 版本（热加载的基础）。
     */
    public synchronized void reload() {
        Path extDir = resolveExternalDir();
        List<SkillDefinition> loaded = extDir != null ? loader.loadAll(extDir) : loader.loadAll();

        Map<String, SkillDefinition> byName = new LinkedHashMap<>();
        Map<String, List<SkillDefinition>> byDomain = new LinkedHashMap<>();
        for (SkillDefinition skill : loaded) {
            byName.put(skill.name(), skill);
            byDomain.computeIfAbsent(skill.domain(), k -> new ArrayList<>()).add(skill);
        }
        Map<String, List<SkillDefinition>> immutableDomains = new LinkedHashMap<>();
        byDomain.forEach((domain, skills) -> immutableDomains.put(domain, List.copyOf(skills)));
        snapshot = new RegistrySnapshot(List.copyOf(loaded), Map.copyOf(byName), Map.copyOf(immutableDomains));
        for (Runnable listener : reloadListeners) {
            try { listener.run(); }
            catch (RuntimeException exception) { log.warn("Skill reload listener failed; registry snapshot retained", exception); }
        }
        log.info("SkillRegistry reloaded: {} skills across {} domains (externalDir={})",
                loaded.size(), byDomain.size(), extDir);
    }

    private Path resolveExternalDir() {
        if (externalDir == null || externalDir.isBlank()) {
            return null;
        }
        Path path = Path.of(externalDir);
        return Files.isDirectory(path) ? path : null;
    }

    /** Listeners observe a fully replaced snapshot and must not perform blocking network work. */
    public AutoCloseable onReload(Runnable listener) {
        reloadListeners.add(listener);
        return () -> reloadListeners.remove(listener);
    }

    public List<SkillDefinition> allSkills() {
        return snapshot.skills();
    }

    public SkillDefinition getByName(String name) {
        return snapshot.byName().get(name);
    }

    /** Returns all skills registered for the given domain (e.g. "trade", "supervisor"). */
    public List<SkillDefinition> findByDomain(String domain) {
        return snapshot.byDomain().getOrDefault(domain, List.of());
    }

    /** Returns only supervisor skills (used for intent knowledge enrichment). */
    public List<SkillDefinition> findSupervisorSkills() {
        return snapshot.skills().stream().filter(SkillDefinition::supervisorSkill).toList();
    }

    /** Returns only operational skills (used by sub-agents for tool filtering). */
    public List<SkillDefinition> findOperationalSkills(String domain) {
        return findByDomain(domain).stream()
                .filter(s -> !s.supervisorSkill())
                .toList();
    }

    public int size() {
        return snapshot.skills().size();
    }
}
