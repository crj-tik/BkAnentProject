package com.bkanent.agent.orchestration;

import com.bkanent.common.skill.core.*;
import com.bkanent.common.skill.runtime.*;
import com.bkanent.common.agent.SkillSelection;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class DemonstrationSkillTest {
    @Test
    void realMarkdownHasExactMixedProtocolScopeAndRequiresExplicitSelection() {
        var registry = new SkillRegistry(new SkillFileLoader(), "");
        var definition = registry.getByName("listing-compare-marketing-draft");
        assertThat(definition).isNotNull(); assertThat(definition.explicitOnly()).isTrue();
        var capabilities = Map.of("a2a:listing-agent", "listing", "mcp:compare-mcp-server:compareListings", "compare", "a2a:marketing-agent", "marketing");
        var resolver = new SkillExecutionResolver(registry);
        var selection = new SkillSelection(definition.name(), "1");
        assertThat(resolver.resolve(selection, "supervisor", capabilities, true).capabilityIds()).containsExactlyInAnyOrderElementsOf(capabilities.keySet());
        assertThatThrownBy(() -> resolver.resolve(selection, "supervisor", capabilities, false)).hasMessageContaining("listing-compare-marketing-draft");
        assertThatThrownBy(() -> resolver.resolve(selection, "supervisor", Map.of("a2a:listing-agent", "listing"), true)).hasMessageContaining("unavailable");
        assertThat(definition.systemPrompt()).contains("listingIds", "marketing-draft", "request_input");
    }
}
