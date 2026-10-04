package com.bkanent.common.a2a;

import com.bkanent.common.agent.SkillPublication;
import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.core.SkillRegistry;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentCapabilities;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillAgentCardPublisherTest {
    @Test
    void allNineOwnersPublishExactVersionHashAndUniqueLocalMappings() {
        for (String owner : List.of("listing", "compare", "marketing", "trade", "media", "contract", "settlement", "notification", "interview")) {
            var registry = mock(SkillRegistry.class);
            var skill = SkillDefinition.builder().name(owner + "-test").description("real description")
                    .domain(owner).tools(List.of("read")).version("3").build();
            when(registry.findOperationalSkills(owner)).thenReturn(List.of(skill));
            var base = new AgentCard(owner, "description", "http://localhost/a2a", null, "1", null,
                    new AgentCapabilities(true, false, true, List.of()), List.of("text"), List.of("text"),
                    List.of(), false, Map.of(), List.of(), null, List.of(), "JSONRPC", "0.2.5");
            var published = SkillAgentCardPublisher.publish(base, registry, owner);
            assertThat(published.skills()).hasSize(1);
            assertThat(published.skills().get(0).name()).isEqualTo(skill.name());
            assertThat(published.capabilities().extensions().get(0).uri()).isEqualTo(SkillPublication.EXTENSION_URI);
            assertThat(published.capabilities().extensions().get(0).params().get("skills"))
                    .isEqualTo(List.of(Map.of("cardSkillId", "bk-skill/" + owner + "/" + skill.name(),
                            "name", skill.name(), "owner", owner, "version", "3", "contentHash", skill.contentHash())));
            assertThat(published.url()).isEqualTo(base.url());
        }
    }
}
