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
        boolean supervisorSkill,
        String owner,
        String version,
        SkillCapabilityPolicy capabilities,
        boolean explicitOnly
) {
    public SkillDefinition {
        owner = owner == null || owner.isBlank() ? domain : owner.trim();
        version = version == null || version.isBlank() ? "1" : version.trim();
        capabilities = capabilities == null ? SkillCapabilityPolicy.legacy() : capabilities;
        triggerKeywords = triggerKeywords == null ? List.of() : List.copyOf(triggerKeywords);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public SkillDefinition(String name, String description, String domain, List<String> triggerKeywords,
                           List<String> tools, String systemPrompt, int priority, boolean supervisorSkill) {
        this(name, description, domain, triggerKeywords, tools, systemPrompt, priority, supervisorSkill,
                domain, "1", SkillCapabilityPolicy.legacy(), false);
    }

    public String contentHash() {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            java.util.stream.Stream.concat(java.util.stream.Stream.of(name, description, domain, owner, version,
                    systemPrompt, capabilities.policy(), String.valueOf(explicitOnly), String.valueOf(supervisorSkill),
                    String.valueOf(priority), String.valueOf(triggerKeywords.size()), String.valueOf(tools.size()),
                    String.valueOf(capabilities.refs().size())),
                    java.util.stream.Stream.concat(triggerKeywords.stream(),
                            java.util.stream.Stream.concat(tools.stream(), capabilities.refs().stream())))
                    .forEach(value -> {
                        byte[] bytes = (value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                        digest.update(bytes);
                    });
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

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
        private String owner;
        private String version;
        private SkillCapabilityPolicy capabilities = SkillCapabilityPolicy.legacy();
        private boolean explicitOnly;

        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder domain(String domain) { this.domain = domain; return this; }
        public Builder triggerKeywords(List<String> triggerKeywords) { this.triggerKeywords = triggerKeywords; return this; }
        public Builder tools(List<String> tools) { this.tools = tools; return this; }
        public Builder systemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; return this; }
        public Builder priority(int priority) { this.priority = priority; return this; }
        public Builder supervisorSkill(boolean supervisorSkill) { this.supervisorSkill = supervisorSkill; return this; }
        public Builder owner(String owner) { this.owner = owner; return this; }
        public Builder version(String version) { this.version = version; return this; }
        public Builder capabilities(SkillCapabilityPolicy capabilities) { this.capabilities = capabilities; return this; }
        public Builder explicitOnly(boolean explicitOnly) { this.explicitOnly = explicitOnly; return this; }

        public SkillDefinition build() {
            if (name == null || name.isBlank()) throw new IllegalStateException("skill name is required");
            if (description == null || description.isBlank()) throw new IllegalStateException("skill description is required");
            if (domain == null || domain.isBlank()) throw new IllegalStateException("skill domain is required");
            return new SkillDefinition(name, description, domain,
                    triggerKeywords == null ? List.of() : List.copyOf(triggerKeywords),
                    tools == null ? List.of() : List.copyOf(tools),
                    systemPrompt == null ? "" : systemPrompt,
                    priority, supervisorSkill, owner, version, capabilities, explicitOnly);
        }
    }
}
