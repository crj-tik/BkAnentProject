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
        List<AgentSkill> skills = new ArrayList<>(card.skills() == null ? List.of() : card.skills());
        Set<String> ids = new HashSet<>();
        skills.forEach(skill -> { if (!ids.add(skill.id())) throw new IllegalArgumentException("duplicate Card skill id"); });
        List<Map<String, Object>> publications = new ArrayList<>();
        registry.findOperationalSkills(owner).forEach(skill -> {
            if (!owner.equals(skill.owner())) throw new IllegalArgumentException("local skill owner mismatch");
            String id = "bk-skill/" + owner + "/" + skill.name();
            if (!ids.add(id)) throw new IllegalArgumentException("duplicate published skill: " + id);
            skills.add(new AgentSkill(id, skill.name(), skill.description(), List.of(owner), List.of(),
                    List.of("text", "application/json"), List.of("text", "application/json")));
            publications.add(Map.of("cardSkillId", id, "name", skill.name(), "owner", owner,
                    "version", skill.version(), "contentHash", skill.contentHash()));
        });
        AgentCapabilities original = card.capabilities();
        List<AgentExtension> extensions = new ArrayList<>(original.extensions() == null ? List.of() : original.extensions());
        extensions.removeIf(extension -> SkillPublication.EXTENSION_URI.equals(extension.uri()));
        extensions.add(new AgentExtension("Request-scoped explicit local skill selection", Map.of(
                "contractVersion", "1", "owner", owner, "skills", List.copyOf(publications)), false,
                SkillPublication.EXTENSION_URI));
        var capabilities = new AgentCapabilities(original.streaming(), original.pushNotifications(),
                original.stateTransitionHistory(), List.copyOf(extensions));
        return new AgentCard(card.name(), card.description(), card.url(), card.provider(), card.version(),
                card.documentationUrl(), capabilities, card.defaultInputModes(), card.defaultOutputModes(),
                List.copyOf(skills), card.supportsAuthenticatedExtendedCard(), card.securitySchemes(), card.security(),
                card.iconUrl(), card.additionalInterfaces(), card.preferredTransport(), card.protocolVersion());
    }
}
