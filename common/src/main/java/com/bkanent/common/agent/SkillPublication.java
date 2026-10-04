package com.bkanent.common.agent;

/** Explicit published Card skill identity; distinct from advisory intent tags. */
public record SkillPublication(String cardSkillId, String name, String owner, String version, String contentHash) {
    public static final String EXTENSION_URI = "urn:bkagent:skill-selection:v1";
    public SkillSelection selection() { return new SkillSelection(name, version, contentHash, owner); }
}
