package com.bkanent.common.a2a;

import com.bkanent.common.agent.SkillPublication;
import com.bkanent.common.skill.core.SkillRegistry;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentExtension;
import io.a2a.spec.AgentSkill;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Adds an exact local-skill mapping to the same Card published over HTTP and Nacos. */
public final class SkillAgentCardPublisher {
    private SkillAgentCardPublisher() {}

    public static AgentCard publish(AgentCard card, SkillRegistry registry, String owner) {
        return publish(card, registry, owner, List.of(owner));
    }

    /** Publishes several isolated internal skill owners under one external Agent Card. */
    public static AgentCard publish(AgentCard card, SkillRegistry registry, String owner, List<String> skillOwners) {
        if (owner == null || owner.isBlank() || skillOwners == null || skillOwners.isEmpty()
                || skillOwners.stream().anyMatch(value -> value == null || value.isBlank())
                || new HashSet<>(skillOwners).size() != skillOwners.size()) {
            throw new IllegalArgumentException("invalid skill owners");
        }
        List<AgentSkill> skills = new ArrayList<>(card.skills() == null ? List.of() : card.skills());
        // Rebuild our generated rows, including deletions, without touching unrelated declarations.
        skills.removeIf(skill -> skill.id() != null && skillOwners.stream()
                .anyMatch(skillOwner -> skill.id().startsWith("bk-skill/" + skillOwner + "/")));
        Set<String> ids = new HashSet<>();
        skills.forEach(skill -> { if (!ids.add(skill.id())) throw new IllegalArgumentException("duplicate Card skill id"); });
        List<Map<String, Object>> publications = new ArrayList<>();
        Set<String> names = new HashSet<>();
        skillOwners.forEach(skillOwner -> registry.findOperationalSkills(skillOwner).forEach(skill -> {
            if (!skillOwner.equals(skill.owner())) throw new IllegalArgumentException("local skill owner mismatch");
            if (!names.add(skill.name())) throw new IllegalArgumentException("duplicate published skill name");
            String id = "bk-skill/" + skillOwner + "/" + skill.name();
            if (!ids.add(id)) throw new IllegalArgumentException("duplicate published skill: " + id);
            skills.add(new AgentSkill(id, skill.name(), skill.description(), List.of(skillOwner), List.of(),
                    List.of("text", "application/json"), List.of("text", "application/json")));
            publications.add(Map.of("cardSkillId", id, "name", skill.name(), "owner", skillOwner,
                    "version", skill.version(), "contentHash", skill.contentHash()));
        }));
        AgentCapabilities original = card.capabilities();
        List<AgentExtension> extensions = new ArrayList<>(original.extensions() == null ? List.of() : original.extensions());
        extensions.removeIf(extension -> SkillPublication.EXTENSION_URI.equals(extension.uri()));
        extensions.add(new AgentExtension("Request-scoped explicit local skill selection", Map.of(
                "contractVersion", "1", "owner", owner, "skillOwners", List.copyOf(skillOwners),
                "skills", List.copyOf(publications)), false,
                SkillPublication.EXTENSION_URI));
        var capabilities = new AgentCapabilities(original.streaming(), original.pushNotifications(),
                original.stateTransitionHistory(), List.copyOf(extensions));
        return new AgentCard(card.name(), card.description(), card.url(), card.provider(), card.version(),
                card.documentationUrl(), capabilities, card.defaultInputModes(), card.defaultOutputModes(),
                List.copyOf(skills), card.supportsAuthenticatedExtendedCard(), card.securitySchemes(), card.security(),
                card.iconUrl(), card.additionalInterfaces(), card.preferredTransport(), card.protocolVersion());
    }
}
