package com.bkanent.agent.skill;

import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.SkillMatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.stream.Collectors.toMap;

/**
 * A {@link ToolCallbackProvider} that dynamically filters the available tool set
 * based on a matched skill.
 *
 * <h3>Usage</h3>
 * <pre>
 * // 1. Build once with ALL tool callbacks
 * SkillAwareToolProvider provider = new SkillAwareToolProvider(allToolCallbacks);
 *
 * // 2. On each request, resolve the filtered provider
 * ToolCallbackProvider effective = provider.resolveFor(skillMatchResult);
 * </pre>
 *
 * <p>When no skill is matched, the full tool set is returned (fallback).</p>
 */
public class SkillAwareToolProvider implements ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(SkillAwareToolProvider.class);

    private final ToolCallback[] allCallbacks;
    private final Map<String, ToolCallback> callbacksByName;
    private final Map<String, ToolCallback[]> skillCache = new ConcurrentHashMap<>();

    public SkillAwareToolProvider(ToolCallback[] allCallbacks) {
        this.allCallbacks = allCallbacks == null ? new ToolCallback[0] : allCallbacks;
        this.callbacksByName = Arrays.stream(this.allCallbacks)
                .collect(toMap(
                        cb -> cb.getToolDefinition().name(),
                        cb -> cb,
                        (existing, duplicate) -> existing  // keep first in case of duplicates
                ));
    }

    /**
     * Resolve the effective tool set for a matched skill.
     * If the skill lists specific tools, returns only those.
     * If no skill matched, returns all tools (fallback).
     * If the skill has an empty tools list, returns all tools.
     */
    public ToolCallbackProvider resolveFor(SkillMatchResult match) {
        if (!match.isMatched() || match.skill().tools().isEmpty()) {
            return this; // fallback: return all tools
        }
        SkillDefinition skill = match.skill();
        ToolCallback[] filtered = skillCache.computeIfAbsent(skill.name(), name -> {
            List<ToolCallback> matched = skill.tools().stream()
                    .map(callbacksByName::get)
                    .filter(Objects::nonNull)
                    .toList();
            if (matched.isEmpty()) {
                log.warn("Skill '{}' listed tools {} but none found in registry, using all tools",
                        skill.name(), skill.tools());
                return allCallbacks;
            }
            log.debug("Skill '{}' loaded {} tools: {}", skill.name(), matched.size(), skill.tools());
            return matched.toArray(new ToolCallback[0]);
        });
        if (filtered == allCallbacks) {
            return this;
        }
        return () -> filtered;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return allCallbacks;
    }

    /** Returns the total number of registered tools. */
    public int totalToolCount() {
        return allCallbacks.length;
    }

    /** Returns a defensive copy of the name→callback index. */
    public Map<String, ToolCallback> toolIndex() {
        return Map.copyOf(callbacksByName);
    }
}
