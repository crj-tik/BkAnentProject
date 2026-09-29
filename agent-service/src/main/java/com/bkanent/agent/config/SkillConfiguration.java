package com.bkanent.agent.config;

import com.bkanent.agent.skill.SkillAwareToolProvider;
import com.bkanent.agent.skill.SupervisorSkillService;
import com.bkanent.common.skill.core.SkillRegistry;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the Supervisor-side Skills system.
 *
 * <p>Core skill infrastructure (SkillFileLoader / SkillRegistry / SkillMatcher /
 * SkillFileWatcher) is provided by the shared {@code common-skill} module's
 * auto-configuration and is not declared here.</p>
 *
 * <p>This configuration only wires Supervisor-specific pieces:</p>
 * <ol>
 *   <li><b>Supervisor knowledge skills</b> — enrich planning context with domain knowledge</li>
 *   <li><b>Supervisor tool routing</b> — dynamically load tools by domain instead of all at once</li>
 * </ol>
 */
@Configuration
public class SkillConfiguration {

    @Bean
    public SupervisorSkillService supervisorSkillService(SkillRegistry registry) {
        return new SupervisorSkillService(registry);
    }

    // ──────────────────────────────────────────────
    // Supervisor: skill-aware tool provider
    // Wraps the combined tool set and enables
    // per-domain or per-skill tool filtering
    // ──────────────────────────────────────────────

    @Bean("skillAwareToolProvider")
    public SkillAwareToolProvider skillAwareToolProvider(
            @Qualifier("combinedToolCallbackProvider") ToolCallbackProvider combinedProvider) {
        return new SkillAwareToolProvider(combinedProvider::getToolCallbacks);
    }

    /**
     * Returns a domain-filtered tool provider for the supervisor.
     * This allows the supervisor to load only the tools relevant to a
     * specific domain (e.g., only trade-domain MCP tools) instead of
     * all 20+ tools from every sub-agent.
     */
    @Bean
    public SkillAwareToolProvider supervisorSkillToolProvider(
            @Qualifier("skillAwareToolProvider") SkillAwareToolProvider fullProvider) {
        return fullProvider;
    }
}
