package com.bkanent.common.skill.runtime;

import com.bkanent.common.agent.CapabilityId;
import com.bkanent.common.agent.SkillSelection;
import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.core.SkillRegistry;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.bkanent.common.agent.OrchestrationErrorCodes.*;

/** Validates identity and capability sets only; never interprets business steps. */
public final class SkillExecutionResolver {
    private final SkillRegistry registry;

    public SkillExecutionResolver(SkillRegistry registry) {
        this.registry = registry;
    }

    public SkillExecutionSnapshot resolve(SkillSelection selection, String owner,
                                          Map<String, String> availableCapabilities, boolean explicit) {
        SkillDefinition skill = registry.getByName(selection.name());
        if (skill == null || skill.supervisorSkill()) throw failure(SKILL_NOT_FOUND, selection.name());
        if (!owner.equals(skill.owner()) || (selection.owner() != null && !owner.equals(selection.owner()))) {
            throw failure(SKILL_OWNER_MISMATCH, selection.name());
        }
        if (selection.version() != null && !selection.version().equals(skill.version())) {
            throw failure(SKILL_VERSION_MISMATCH, selection.name());
        }
        if (selection.contentHash() != null && !selection.contentHash().equals(skill.contentHash())) {
            throw failure(SKILL_CONTENT_MISMATCH, selection.name());
        }
        if (!explicit && skill.explicitOnly()) throw failure(SKILL_EXPLICIT_REQUIRED, skill.name());
        Set<String> refs = new LinkedHashSet<>();
        if ("inherit".equals(skill.capabilities().policy())) {
            refs.addAll(availableCapabilities.keySet());
        } else if ("allowlist".equals(skill.capabilities().policy())) {
            refs.addAll(skill.capabilities().refs());
        } else if (!skill.tools().isEmpty()) {
            skill.tools().forEach(tool -> refs.add(CapabilityId.local(tool).value()));
        } else if (!explicit) {
            refs.addAll(availableCapabilities.keySet());
        } else {
            throw failure(SKILL_POLICY_INVALID, "explicit skill requires allowlist or explicit inherit");
        }
        if (refs.isEmpty() || !availableCapabilities.keySet().containsAll(refs)) {
            throw failure(SKILL_POLICY_INVALID, "skill capability is unavailable, forbidden or scope is empty");
        }
        return new SkillExecutionSnapshot(skill, skill.contentHash(), refs, explicit ? "EXPLICIT" : "MODEL");
    }

    private SkillExecutionException failure(String code, String message) {
        return new SkillExecutionException(code, message);
    }
}
