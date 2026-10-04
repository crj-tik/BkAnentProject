package com.bkanent.common.skill.runtime;

import com.bkanent.common.agent.SkillSelection;
import com.bkanent.common.skill.SkillCapabilityPolicy;
import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.core.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillExecutionResolverTest {
    private final SkillRegistry registry = mock(SkillRegistry.class);
    private final SkillExecutionResolver resolver = new SkillExecutionResolver(registry);
    private final Map<String, String> tools = Map.of("local:search", "search", "local:detail", "detail");

    @Test
    void legacyNonEmptyToolsAreStrictButEmptyExplicitDoesNotExpand() {
        SkillDefinition skill = skill(List.of("search"), false);
        when(registry.getByName("find")).thenReturn(skill);
        assertThat(resolver.resolve(new SkillSelection("find", null), "listing", tools, true).capabilityIds())
                .containsExactly("local:search");
        when(registry.getByName("find")).thenReturn(skill(List.of(), false));
        assertThatThrownBy(() -> resolver.resolve(new SkillSelection("find", null), "listing", tools, true))
                .isInstanceOf(SkillExecutionException.class).hasMessageContaining("requires allowlist");
        assertThat(resolver.resolve(new SkillSelection("find", null), "listing", tools, false).capabilityIds())
                .containsExactlyInAnyOrderElementsOf(tools.keySet());
    }

    @Test
    void rejectsMissingReferencesOwnerVersionHashAndAutomaticExplicitOnly() {
        SkillDefinition skill = skill(List.of("missing"), true);
        when(registry.getByName("find")).thenReturn(skill);
        assertThatThrownBy(() -> resolver.resolve(new SkillSelection("find", null), "listing", tools, false))
                .isInstanceOf(SkillExecutionException.class).hasMessage("find");
        assertThatThrownBy(() -> resolver.resolve(new SkillSelection("find", "wrong"), "listing", tools, true))
                .isInstanceOf(SkillExecutionException.class);
        assertThatThrownBy(() -> resolver.resolve(new SkillSelection("find", "1", "wrong", "listing"),
                "listing", tools, true)).isInstanceOf(SkillExecutionException.class);
        assertThatThrownBy(() -> resolver.resolve(new SkillSelection("find", null), "contract", tools, true))
                .isInstanceOf(SkillExecutionException.class);
        assertThatThrownBy(() -> resolver.resolve(new SkillSelection("find", null), "listing", tools, true))
                .hasMessageContaining("unavailable");
    }

    @Test
    void snapshotRetainsOldBodyAndScopeAfterRegistryUpdate() {
        SkillDefinition original = skill(List.of("search"), false);
        when(registry.getByName("find")).thenReturn(original);
        SkillExecutionSnapshot snapshot = resolver.resolve(new SkillSelection("find", null), "listing", tools, true);
        when(registry.getByName("find")).thenReturn(SkillDefinition.builder().name("find").description("find")
                .domain("listing").tools(List.of("detail")).systemPrompt("updated body").version("2").build());
        assertThat(snapshot.definition().systemPrompt()).isEqualTo("understand then search");
        assertThat(snapshot.capabilityIds()).containsExactly("local:search");
        assertThat(snapshot.contentHash()).isEqualTo(original.contentHash());
    }

    @Test
    void inheritUsesOnlyProvidedAuthorizedCapabilities() {
        when(registry.getByName("find")).thenReturn(SkillDefinition.builder().name("find").description("find")
                .domain("listing").capabilities(new SkillCapabilityPolicy("inherit", List.of())).build());
        assertThat(resolver.resolve(new SkillSelection("find", null), "listing", Map.of("local:search", "search"),
                true).capabilityIds()).containsExactly("local:search");
    }

    private SkillDefinition skill(List<String> allowed, boolean explicitOnly) {
        return SkillDefinition.builder().name("find").description("find").domain("listing").tools(allowed)
                .systemPrompt("understand then search").explicitOnly(explicitOnly).build();
    }
}
