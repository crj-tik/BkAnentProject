package com.bkanent.agent.orchestration;

import com.bkanent.common.agent.SkillSelection;
import com.bkanent.common.skill.core.SkillFileLoader;
import com.bkanent.common.skill.core.SkillRegistry;
import com.bkanent.common.skill.runtime.SkillExecutionResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketingCampaignPublishSkillTest {

    @Test
    void marketingPublishSkillIsExplicitAndBoundToMarketingCapabilities() {
        var registry = new SkillRegistry(new SkillFileLoader(), "");
        var skill = registry.getByName("marketing-campaign-publish");

        assertThat(skill).isNotNull();
        assertThat(registry.findOperationalSkills("supervisor")).extracting(item -> item.name())
                .contains("marketing-campaign-publish");
        assertThat(skill.explicitOnly()).isTrue();
        assertThat(skill.supervisorSkill()).isFalse();
        assertThat(skill.capabilities().policy()).isEqualTo("allowlist");
        assertThat(skill.capabilities().refs()).containsExactlyInAnyOrder(
                "a2a:marketing-agent",
                "mcp:marketing-mcp-server:createMarketingContentDraft",
                "mcp:marketing-mcp-server:prepareMarketingCampaign",
                "mcp:marketing-mcp-server:confirmMarketingCampaign",
                "mcp:marketing-mcp-server:getMarketingCampaignReceipt",
                "mcp:marketing-mcp-server:reconcileMarketingCampaignAttempt");

        var capabilities = skill.capabilities().refs().stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> id));
        var resolver = new SkillExecutionResolver(registry);
        assertThat(resolver.resolve(new SkillSelection(skill.name(), "1"), "supervisor", capabilities, true)
                .capabilityIds()).containsExactlyInAnyOrderElementsOf(capabilities.keySet());
        assertThatThrownBy(() -> resolver.resolve(new SkillSelection(skill.name(), "1"), "supervisor", capabilities, false))
                .hasMessageContaining("marketing-campaign-publish");

        var readOnlyMarketingSkill = registry.getByName("listing-compare-marketing-draft");
        assertThat(readOnlyMarketingSkill.systemPrompt()).contains("只提供读取既有内容的能力", "不创建发布任务", "不调用 publishContent");
        assertThat(skill.systemPrompt()).contains("APPROVED", "OUTCOME_UNKNOWN", "approval-capabilities", "provider receipt");
    }
}
