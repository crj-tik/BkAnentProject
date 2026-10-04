package com.bkanent.common.agent;

/** Explicit selection is separate from the advisory skillHint (LR-9). */
public record SkillSelection(String name, String version, String contentHash, String owner) {

    public SkillSelection {
        name = normalize(name);
        version = normalize(version);
        contentHash = normalize(contentHash);
        owner = normalize(owner);
        if (name == null) {
            throw new IllegalArgumentException("skill name is required");
        }
    }

    public SkillSelection(String name, String version) {
        this(name, version, null, null);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
