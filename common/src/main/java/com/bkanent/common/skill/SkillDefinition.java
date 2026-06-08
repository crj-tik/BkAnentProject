package com.bkanent.common.skill;

import java.util.List;

/**
 * Parsed from a skill.md file. The YAML frontmatter becomes the metadata fields;
 * the Markdown body becomes {@link #systemPrompt()}.
 *
 * @param name           unique skill identifier, e.g. "trade-kpi-report"
 * @param description    human-readable one-liner used for matching and display
 * @param domain         which domain/agent this skill belongs to (trade, compare, marketing, supervisor, ...)
 * @param triggerKeywords keywords used for fast rule-based intent matching
 * @param tools          tool names to load when this skill is activated; empty means "all tools"
 * @param systemPrompt   the skill-specific instruction injected as the system prompt when matched
 * @param priority       higher value = higher priority when multiple skills match
 * @param supervisorSkill true if this is a supervisor knowledge skill (for intent enrichment)
 */
public record SkillDefinition(
        String name,
        String description,
        String domain,
        List<String> triggerKeywords,
        List<String> tools,
        String systemPrompt,
        int priority,
        boolean supervisorSkill
) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private String description;
        private String domain;
        private List<String> triggerKeywords = List.of();
        private List<String> tools = List.of();
        private String systemPrompt;
        private int priority = 5;
        private boolean supervisorSkill;

        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder domain(String domain) { this.domain = domain; return this; }
        public Builder triggerKeywords(List<String> triggerKeywords) { this.triggerKeywords = triggerKeywords; return this; }
        public Builder tools(List<String> tools) { this.tools = tools; return this; }
        public Builder systemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; return this; }
        public Builder priority(int priority) { this.priority = priority; return this; }
        public Builder supervisorSkill(boolean supervisorSkill) { this.supervisorSkill = supervisorSkill; return this; }

        public SkillDefinition build() {
            if (name == null || name.isBlank()) throw new IllegalStateException("skill name is required");
            if (description == null || description.isBlank()) throw new IllegalStateException("skill description is required");
            if (domain == null || domain.isBlank()) throw new IllegalStateException("skill domain is required");
            return new SkillDefinition(name, description, domain,
                    triggerKeywords == null ? List.of() : List.copyOf(triggerKeywords),
                    tools == null ? List.of() : List.copyOf(tools),
                    systemPrompt == null ? "" : systemPrompt,
                    priority, supervisorSkill);
        }
    }
}
