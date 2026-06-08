package com.bkanent.agent.skill;

import com.bkanent.common.skill.SkillDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Value;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central registry for all loaded skills. Provides indexed lookups by name,
 * domain, and supervisor-mode.
 *
 * <p>Skills are loaded at startup from classpath:skills/**&#47;*.md.
 * The registry can be refreshed at runtime via {@link #reload()}.</p>
 */
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final SkillFileLoader loader;

    @Value("${agent.skills.external-dir:}")
    private String externalDir;

    private final List<SkillDefinition> skills = new CopyOnWriteArrayList<>();
    private final Map<String, SkillDefinition> byName = new LinkedHashMap<>();
    private final Map<String, List<SkillDefinition>> byDomain = new LinkedHashMap<>();

    public SkillRegistry(SkillFileLoader loader) {
        this.loader = loader;
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

        skills.clear();
        byName.clear();
        byDomain.clear();
        for (SkillDefinition skill : loaded) {
            skills.add(skill);
            byName.put(skill.name(), skill);
            byDomain.computeIfAbsent(skill.domain(), k -> new ArrayList<>()).add(skill);
        }
        log.info("SkillRegistry reloaded: {} skills across {} domains (externalDir={})",
                skills.size(), byDomain.size(), extDir);
    }

    private Path resolveExternalDir() {
        if (externalDir == null || externalDir.isBlank()) {
            return null;
        }
        Path path = Path.of(externalDir);
        return Files.isDirectory(path) ? path : null;
    }

    public List<SkillDefinition> allSkills() {
        return List.copyOf(skills);
    }

    public SkillDefinition getByName(String name) {
        return byName.get(name);
    }

    /** Returns all skills registered for the given domain (e.g. "trade", "supervisor"). */
    public List<SkillDefinition> findByDomain(String domain) {
        return byDomain.getOrDefault(domain, List.of());
    }

    /** Returns only supervisor skills (used for intent knowledge enrichment). */
    public List<SkillDefinition> findSupervisorSkills() {
        return skills.stream().filter(SkillDefinition::supervisorSkill).toList();
    }

    /** Returns only operational skills (used by sub-agents for tool filtering). */
    public List<SkillDefinition> findOperationalSkills(String domain) {
        return findByDomain(domain).stream()
                .filter(s -> !s.supervisorSkill())
                .toList();
    }

    public int size() {
        return skills.size();
    }
}
