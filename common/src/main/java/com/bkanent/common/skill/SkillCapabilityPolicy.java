package com.bkanent.common.skill;

import java.util.List;

/** Only a capability set: intentionally has no business steps or dependencies. */
public record SkillCapabilityPolicy(String policy, List<String> refs) {
    public SkillCapabilityPolicy {
        policy = policy == null ? "legacy" : policy.trim();
        if (!List.of("legacy", "allowlist", "inherit").contains(policy)) {
            throw new IllegalArgumentException("unknown skill capability policy: " + policy);
        }
        refs = refs == null ? List.of() : List.copyOf(refs);
        if (refs.stream().anyMatch(ref -> ref == null || ref.isBlank())) {
            throw new IllegalArgumentException("blank skill capability reference");
        }
        if ("inherit".equals(policy) && !refs.isEmpty()) {
            throw new IllegalArgumentException("inherit cannot declare capability references");
        }
    }

    public static SkillCapabilityPolicy legacy() {
        return new SkillCapabilityPolicy("legacy", List.of());
    }
}
