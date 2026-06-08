package com.bkanent.agent.config;

import com.bkanent.agent.skill.SkillAwareToolProvider;
import com.bkanent.agent.skill.SkillFileLoader;
import com.bkanent.agent.skill.SkillFileWatcher;
import com.bkanent.agent.skill.SkillMatcher;
import com.bkanent.agent.skill.SkillRegistry;
import com.bkanent.agent.skill.SupervisorSkillService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the Skills system.
 *
 * <p>Three layers of skill support:</p>
 * <ol>
 *   <li><b>Supervisor knowledge skills</b> — enrich planning context with domain knowledge</li>
 *   <li><b>Supervisor tool routing</b> — dynamically load tools by domain instead of all at once</li>
 *   <li><b>Sub-agent operational skills</b> — filter tools + customize prompt per skill.md</li>
 * </ol>
 */
@Configuration
public class SkillConfiguration {

    // ──────────────────────────────────────────────
    // Core infrastructure
    // ──────────────────────────────────────────────

    @Bean
    public SkillFileLoader skillFileLoader() {
        return new SkillFileLoader();
    }

    @Bean
    public SkillRegistry skillRegistry(SkillFileLoader loader) {
        return new SkillRegistry(loader);
    }

    @Bean
    public SkillMatcher skillMatcher(SkillRegistry registry) {
        return new SkillMatcher(registry);
    }

    @Bean
    public SupervisorSkillService supervisorSkillService(SkillRegistry registry) {
        return new SupervisorSkillService(registry);
    }

    // ──────────────────────────────────────────────
    // 热加载：文件监控（类似 Claude Code 新增 skill 即生效）
    // ──────────────────────────────────────────────

    @Bean
    public SkillFileWatcher skillFileWatcher(SkillRegistry registry) {
        return new SkillFileWatcher(registry);
    }

    // ──────────────────────────────────────────────
    // Supervisor: skill-aware tool provider
    // Wraps the combined tool set and enables
    // per-domain or per-skill tool filtering
    // ──────────────────────────────────────────────

    @Bean("skillAwareToolProvider")
    public SkillAwareToolProvider skillAwareToolProvider(
            @Qualifier("combinedToolCallbackProvider") ToolCallbackProvider combinedProvider) {
        ToolCallback[] allCallbacks = combinedProvider.getToolCallbacks();
        return new SkillAwareToolProvider(allCallbacks);
    }

    /**
     * Returns a domain-filtered tool provider for the supervisor.
     * This allows the supervisor to load only the tools relevant to a
     * specific domain (e.g., only trade-domain MCP tools) instead of
     * all 20+ tools from every sub-agent.
     *
     * <p>Usage in {@code AgentChatService}:
     * <pre>
     * SkillMatchResult match = skillMatcher.match(userMessage, resolvedDomain);
     * ToolCallbackProvider effectiveTools = skillAwareToolProvider.resolveFor(match);
     * ChatClient client = ChatClient.builder(chatModel)
     *         .defaultToolCallbacks(effectiveTools)
     *         .build();
     * </pre>
     */
    @Bean
    public SkillAwareToolProvider supervisorSkillToolProvider(
            @Qualifier("skillAwareToolProvider") SkillAwareToolProvider fullProvider) {
        return fullProvider;
    }
}
