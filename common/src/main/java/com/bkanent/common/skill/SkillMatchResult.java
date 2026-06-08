package com.bkanent.common.skill;

/**
 * Result of matching a user message against registered skills.
 *
 * @param skill        the matched skill, or null if no match
 * @param score        confidence score 0.0–1.0
 * @param matchStrategy which strategy produced the match (keyword, semantic, llm, none)
 */
public record SkillMatchResult(
        SkillDefinition skill,
        double score,
        String matchStrategy
) {

    private static final SkillMatchResult NO_MATCH = new SkillMatchResult(null, 0.0, "none");

    public static SkillMatchResult noMatch() {
        return NO_MATCH;
    }

    public static SkillMatchResult of(SkillDefinition skill, double score, String strategy) {
        return new SkillMatchResult(skill, score, strategy);
    }

    public boolean isMatched() {
        return skill != null && score > 0.0;
    }
}
