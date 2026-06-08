package com.bkanent.agent.graph.node;

import com.bkanent.agent.graph.SupervisorGraphNode;
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.skill.SupervisorSkillService;
import com.bkanent.common.skill.SkillMatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Injects knowledge-domain context from matched supervisor skills
 * before the LLM intent planning step.
 *
 * <p>Inserted between LoadSession and LlmIntentPlan in the planning graph.
 * When a supervisor knowledge skill matches, its prompt content is added
 * to sharedContext, which the LlmIntentPlanNode then includes in its LLM call.</p>
 */
@Component
public class SkillMatchNode implements SupervisorGraphNode {

    private static final Logger log = LoggerFactory.getLogger(SkillMatchNode.class);

    private final SupervisorSkillService supervisorSkillService;

    public SkillMatchNode(SupervisorSkillService supervisorSkillService) {
        this.supervisorSkillService = supervisorSkillService;
    }

    @Override
    public SupervisorGraphState apply(SupervisorGraphState state) {
        if (!supervisorSkillService.hasSupervisorSkills()) {
            return state;
        }

        String userMessage = state.userMessage();
        if (userMessage == null || userMessage.isBlank()) {
            return state;
        }

        SkillMatchResult match = supervisorSkillService.matchKnowledge(userMessage);
        if (!match.isMatched()) {
            return state;
        }

        Map<String, Object> enrichedContext = supervisorSkillService.enrichContext(
                state.sharedContext(), match);

        log.info("Supervisor skill '{}' enriched planning context for session={}",
                match.skill().name(), state.sessionId());

        return state.withSharedContext(enrichedContext);
    }
}
