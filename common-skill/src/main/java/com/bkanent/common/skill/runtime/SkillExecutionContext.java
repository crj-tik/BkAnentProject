package com.bkanent.common.skill.runtime;

import com.bkanent.common.agent.SkillSelection;
import com.bkanent.common.skill.core.SkillRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static com.bkanent.common.agent.OrchestrationErrorCodes.*;

/** Request-owned state shared by model and tool interceptors; no business step state. */
public final class SkillExecutionContext {
    public static final String KEY = "bkSkillExecution";
    private final String owner;
    private final String originalTask;
    private final SkillExecutionResolver resolver;
    private final Map<String, String> authorizedCapabilities;
    private final boolean explicit;
    private volatile SkillExecutionSnapshot snapshot;

    public SkillExecutionContext(SkillRegistry registry, String owner, String originalTask,
                                  Map<String, String> authorizedCapabilities, SkillSelection selection) {
        this.owner = owner;
        this.originalTask = originalTask;
        this.resolver = new SkillExecutionResolver(registry);
        this.authorizedCapabilities = Map.copyOf(authorizedCapabilities);
        this.explicit = selection != null;
        if (explicit) snapshot = resolver.resolve(selection, owner, authorizedCapabilities, true);
    }

    public synchronized SkillExecutionSnapshot activate(SkillSelection selection) {
        if (explicit) {
            var definition = snapshot.definition();
            if (!definition.name().equals(selection.name())
                    || (selection.version() != null && !definition.version().equals(selection.version()))
                    || (selection.contentHash() != null && !snapshot.contentHash().equals(selection.contentHash()))
                    || (selection.owner() != null && !owner.equals(selection.owner()))) {
                throw new SkillExecutionException(SKILL_SELECTION_LOCKED, "explicit skill cannot be replaced");
            }
            return snapshot;
        }
        SkillExecutionSnapshot loaded = resolver.resolve(selection, owner, availableCapabilities(), false);
        snapshot = loaded;
        return loaded;
    }

    public static SkillExecutionContext restored(SkillRegistry registry, String owner, String originalTask,
                                                 Map<String, String> authorizedCapabilities,
                                                 SkillExecutionSnapshot snapshot, boolean explicit) {
        return new SkillExecutionContext(registry, owner, originalTask, authorizedCapabilities, snapshot, explicit);
    }

    private SkillExecutionContext(SkillRegistry registry, String owner, String originalTask,
                                  Map<String, String> authorizedCapabilities, SkillExecutionSnapshot snapshot, boolean explicit) {
        this.owner = owner; this.originalTask = originalTask; this.resolver = new SkillExecutionResolver(registry);
        this.authorizedCapabilities = Map.copyOf(authorizedCapabilities); this.explicit = explicit;
        if (snapshot != null && (!owner.equals(snapshot.definition().owner())
                || !authorizedCapabilities.keySet().containsAll(snapshot.capabilityIds()))) {
            throw new SkillExecutionException(SKILL_POLICY_INVALID, "invalid restored scope");
        }
        this.snapshot = snapshot;
    }

    public synchronized void restore(SkillExecutionSnapshot restored) {
        if (!owner.equals(restored.definition().owner())
                || !authorizedCapabilities.keySet().containsAll(restored.capabilityIds())
                || (explicit && !Objects.equals(snapshot, restored))) {
            throw new SkillExecutionException(SKILL_POLICY_INVALID, "snapshot exceeds request identity or scope");
        }
        snapshot = restored;
    }

    public Map<String, String> availableCapabilities() {
        SkillExecutionSnapshot active = snapshot;
        if (active == null) return authorizedCapabilities;
        Map<String, String> result = new LinkedHashMap<>();
        active.capabilityIds().forEach(id -> result.put(id, authorizedCapabilities.get(id)));
        return Map.copyOf(result);
    }

    public void assertToolAllowed(String name) {
        if (!SkillTool.TOOL_NAME.equals(name) && !availableCapabilities().containsValue(name)) {
            throw new SkillExecutionException(CAPABILITY_SCOPE_DENIED, "tool is outside active scope: " + name);
        }
    }

    public SkillExecutionSnapshot snapshot() { return snapshot; }
    public String originalTask() { return originalTask; }
    public boolean explicit() { return explicit; }

    public static SkillExecutionContext from(Map<String, Object> context) {
        Object value = context == null ? null : context.get(KEY);
        return value instanceof SkillExecutionContext execution ? execution : null;
    }
}
