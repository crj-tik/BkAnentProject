package com.bkanent.agent.config;

import com.bkanent.agent.skill.SkillAwareToolProvider;
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
 * <p>This configuration exposes the combined capability set to the generic
 * model-tool loop. Skill loading and capability scoping are handled by the
 * shared skill runtime and orchestration request snapshot.</p>
 */
@Configuration
public class SkillConfiguration {

    // Exposes the combined callbacks to the capability catalog.
    @Bean("skillAwareToolProvider")
    public SkillAwareToolProvider skillAwareToolProvider(
            @Qualifier("combinedToolCallbackProvider") ToolCallbackProvider combinedProvider) {
        return new SkillAwareToolProvider(combinedProvider::getToolCallbacks);
    }

    /** Compatibility qualifier used by existing capability catalog wiring. */
    @Bean
    public SkillAwareToolProvider supervisorSkillToolProvider(
            @Qualifier("skillAwareToolProvider") SkillAwareToolProvider fullProvider) {
        return fullProvider;
    }
}
