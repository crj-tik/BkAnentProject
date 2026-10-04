package com.bkanent.common.skill.runtime;

import com.bkanent.common.skill.SkillDefinition;

import java.util.Set;

/** Immutable successful load, stored with a run rather than inferred from attempted calls. */
public record SkillExecutionSnapshot(SkillDefinition definition, String contentHash,
                                     Set<String> capabilityIds, String source) {
    public SkillExecutionSnapshot {
        capabilityIds = Set.copyOf(capabilityIds);
        if (!definition.contentHash().equals(contentHash)) {
            throw new IllegalArgumentException("skill snapshot content does not match its identity");
        }
    }
}
