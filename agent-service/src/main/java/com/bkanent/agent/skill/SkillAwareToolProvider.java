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
import java.util.function.Supplier;

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

    private final Supplier<ToolCallback[]> callbacksSupplier;

    public SkillAwareToolProvider(ToolCallback[] allCallbacks) {
        this(() -> allCallbacks);
    }

    /**
     * Creates a provider whose source is read each time tools are requested.
     * This is important for runtime MCP registration: a provider created during
     * application startup must not permanently freeze the initial tool set.
     */
    public SkillAwareToolProvider(Supplier<ToolCallback[]> callbacksSupplier) {
        this.callbacksSupplier = Objects.requireNonNull(callbacksSupplier, "callbacksSupplier");
    }

    /**
     * Resolve the effective tool set for a matched skill.
     * If the skill lists specific tools, returns only those.
     * If no skill matched, returns all tools (fallback).
     * If the skill has an empty tools list, returns all tools.
     */
    public ToolCallbackProvider resolveFor(SkillMatchResult match) {
        ToolCallback[] allCallbacks = currentCallbacks();
        if (match == null || !match.isMatched() || match.skill().tools().isEmpty()) {
            return this; // fallback: return all tools
        }
        SkillDefinition skill = match.skill();
        Map<String, ToolCallback> callbacksByName = indexByName(allCallbacks);
        List<ToolCallback> matched = skill.tools().stream()
                .map(callbacksByName::get)
                .filter(Objects::nonNull)
                .toList();
        if (matched.isEmpty()) {
            log.warn("Skill '{}' listed tools {} but none found in registry, using all tools",
                    skill.name(), skill.tools());
            return this;
        }
        log.debug("Skill '{}' loaded {} tools: {}", skill.name(), matched.size(), skill.tools());
        ToolCallback[] filtered = matched.toArray(new ToolCallback[0]);
        return () -> filtered;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return currentCallbacks();
    }

    /** Returns the total number of registered tools. */
    public int totalToolCount() {
        return currentCallbacks().length;
    }

    /** Returns a defensive copy of the name→callback index. */
    public Map<String, ToolCallback> toolIndex() {
        return Map.copyOf(indexByName(currentCallbacks()));
    }

    private ToolCallback[] currentCallbacks() {
        ToolCallback[] callbacks = callbacksSupplier.get();
        return callbacks == null ? new ToolCallback[0] : Arrays.copyOf(callbacks, callbacks.length);
    }

    private Map<String, ToolCallback> indexByName(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)
                .collect(toMap(
                        cb -> cb.getToolDefinition().name(),
                        cb -> cb,
                        (existing, duplicate) -> existing
                ));
    }
}
