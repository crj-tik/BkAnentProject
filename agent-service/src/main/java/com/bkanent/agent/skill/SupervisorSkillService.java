package com.bkanent.agent.skill;

import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.SkillMatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages supervisor-level skills for intent knowledge enrichment.
 *
 * <p>Supervisor skills are NOT operational tools — they are knowledge-domain
 * prompts that get injected into the planning context to help the supervisor
 * better understand the user's intent. For example, a "real-estate-knowledge"
 * skill might inject industry terminology definitions, tax rules, or
 * regulatory constraints that improve the planner's domain understanding.</p>
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>User message arrives at the planning graph</li>
 *   <li>{@link #matchKnowledge(String)} checks against supervisor skills</li>
 *   <li>If matched, the skill's systemPrompt is injected into sharedContext
 *       under key {@code "skillKnowledge"}</li>
 *   <li>Downstream nodes (LlmIntentPlan, ParseIntent) see this enriched context</li>
 * </ol>
 */
@Service
public class SupervisorSkillService {

    private static final Logger log = LoggerFactory.getLogger(SupervisorSkillService.class);
    public static final String SKILL_KNOWLEDGE_KEY = "skillKnowledge";
    public static final String MATCHED_SKILL_KEY = "matchedSkillName";

    private final SkillRegistry registry;
    private final SkillMatcher matcher;

    public SupervisorSkillService(SkillRegistry registry) {
        this.registry = registry;
        this.matcher = new SkillMatcher(registry);
    }

    /**
     * Match the user message against supervisor skills for knowledge enrichment.
     */
    public SkillMatchResult matchKnowledge(String userMessage) {
        SkillMatchResult result = matcher.matchSupervisorSkills(userMessage);
        if (result.isMatched()) {
            log.info("Supervisor knowledge skill matched: {} (score={})",
                    result.skill().name(), result.score());
        }
        return result;
    }

    /**
     * Enrich the shared context with knowledge from a matched supervisor skill.
     * Safe to call even when no skill matched (returns unchanged context).
     */
    public Map<String, Object> enrichContext(Map<String, Object> existingContext, SkillMatchResult match) {
        if (!match.isMatched()) {
            return existingContext;
        }
        SkillDefinition skill = match.skill();
        Map<String, Object> enriched = new LinkedHashMap<>(existingContext != null ? existingContext : Map.of());
        enriched.put(MATCHED_SKILL_KEY, skill.name());
        enriched.put(SKILL_KNOWLEDGE_KEY, skill.systemPrompt());

        // Also inject trigger keywords as context hints
        if (!skill.triggerKeywords().isEmpty()) {
            enriched.put("skillKeywords", List.copyOf(skill.triggerKeywords()));
        }

        log.debug("Enriched supervisor context with skill '{}' knowledge ({} chars)",
                skill.name(), skill.systemPrompt().length());
        return enriched;
    }

    /**
     * Returns whether any supervisor skills are registered.
     */
    public boolean hasSupervisorSkills() {
        return !registry.findSupervisorSkills().isEmpty();
    }
}
