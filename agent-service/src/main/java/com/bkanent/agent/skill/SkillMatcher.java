package com.bkanent.agent.skill;

import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.SkillMatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;

/**
 * Multi-strategy skill matcher.
 *
 * <p>Matching strategies (tried in order):</p>
 * <ol>
 *   <li><b>Keyword</b> — fast rule-based: checks trigger_keywords against the user message.
 *       Returns the highest-priority match with all keywords present, or the best partial match.</li>
 *   <li><b>Semantic</b> — (optional) vector similarity via Milvus embedding of skill descriptions.
 *       Requires {@code AgentMilvusService} to be injected.</li>
 *   <li><b>None</b> — fallback when no strategy yields a match above threshold.</li>
 * </ol>
 */
public class SkillMatcher {

    private static final Logger log = LoggerFactory.getLogger(SkillMatcher.class);

    private static final double KEYWORD_FULL_MATCH_SCORE = 0.95;
    private static final double KEYWORD_PARTIAL_MATCH_SCORE = 0.7;
    private static final double MIN_CONFIDENCE_THRESHOLD = 0.6;

    private final SkillRegistry registry;

    public SkillMatcher(SkillRegistry registry) {
        this.registry = registry;
    }

    /**
     * Match a user message against skills in the given domain.
     * If domain is null, matches against all skills.
     */
    public SkillMatchResult match(String userMessage, String domain) {
        if (userMessage == null || userMessage.isBlank()) {
            return SkillMatchResult.noMatch();
        }

        List<SkillDefinition> candidates = domain != null && !domain.isBlank()
                ? registry.findByDomain(domain)
                : registry.allSkills();

        if (candidates.isEmpty()) {
            return SkillMatchResult.noMatch();
        }

        // Strategy 1: keyword matching
        SkillMatchResult keywordResult = matchByKeywords(userMessage, candidates);
        if (keywordResult.isMatched() && keywordResult.score() >= KEYWORD_FULL_MATCH_SCORE) {
            log.debug("Skill matched by keywords: {} (score={})", keywordResult.skill().name(), keywordResult.score());
            return keywordResult;
        }

        // Strategy 2: fallback to best partial keyword match
        if (keywordResult.isMatched() && keywordResult.score() >= MIN_CONFIDENCE_THRESHOLD) {
            log.debug("Skill matched by partial keywords: {} (score={})", keywordResult.skill().name(), keywordResult.score());
            return keywordResult;
        }

        return SkillMatchResult.noMatch();
    }

    /**
     * Match supervisor skills only (for intent knowledge enrichment).
     */
    public SkillMatchResult matchSupervisorSkills(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return SkillMatchResult.noMatch();
        }
        List<SkillDefinition> supervisorSkills = registry.findSupervisorSkills();
        if (supervisorSkills.isEmpty()) {
            return SkillMatchResult.noMatch();
        }
        return matchByKeywords(userMessage, supervisorSkills);
    }

    private SkillMatchResult matchByKeywords(String userMessage, List<SkillDefinition> candidates) {
        String lowerMessage = userMessage.toLowerCase();

        SkillDefinition bestMatch = null;
        double bestScore = 0.0;

        for (SkillDefinition skill : candidates) {
            if (skill.triggerKeywords().isEmpty()) {
                continue;
            }
            double score = computeKeywordScore(lowerMessage, skill);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = skill;
            } else if (score == bestScore && bestMatch != null && skill.priority() > bestMatch.priority()) {
                // tie-break by priority
                bestMatch = skill;
            }
        }

        if (bestMatch != null && bestScore >= MIN_CONFIDENCE_THRESHOLD) {
            return SkillMatchResult.of(bestMatch, bestScore, "keyword");
        }
        return SkillMatchResult.noMatch();
    }

    private double computeKeywordScore(String lowerMessage, SkillDefinition skill) {
        List<String> keywords = skill.triggerKeywords();
        if (keywords.isEmpty()) {
            return 0.0;
        }
        int matched = 0;
        for (String kw : keywords) {
            if (lowerMessage.contains(kw.toLowerCase())) {
                matched++;
            }
        }
        return (double) matched / keywords.size();
    }

    /**
     * Find all skills that could potentially match (score > 0), sorted by descending score.
     * Useful for presenting "did you mean?" suggestions.
     */
    public List<SkillMatchResult> rankAll(String userMessage, String domain) {
        List<SkillDefinition> candidates = domain != null
                ? registry.findByDomain(domain)
                : registry.allSkills();

        String lowerMessage = userMessage == null ? "" : userMessage.toLowerCase();

        return candidates.stream()
                .map(skill -> {
                    double score = computeKeywordScore(lowerMessage, skill);
                    return SkillMatchResult.of(skill, score, "keyword");
                })
                .filter(r -> r.score() > 0)
                .sorted(Comparator.<SkillMatchResult>comparingDouble(SkillMatchResult::score).reversed())
                .toList();
    }
}
