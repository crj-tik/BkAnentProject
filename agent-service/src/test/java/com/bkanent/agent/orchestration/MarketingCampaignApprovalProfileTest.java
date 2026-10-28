package com.bkanent.agent.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingCampaignApprovalProfileTest {

    @Test
    void isolatedTestProfileContainsOnlyMarketingConfirmationApprovalCapability() throws Exception {
        var source = new YamlPropertySourceLoader().load("marketing-campaign-test",
                new ClassPathResource("application-marketing-campaign-test.yml")).get(0);
        var environment = new MockEnvironment();
        environment.getPropertySources().addLast(source);

        var properties = Binder.get(environment).bind("agent.supervisor.orchestration",
                Bindable.of(SupervisorOrchestrationProperties.class)).orElseThrow(AssertionError::new);

        assertThat(properties.getApprovalCapabilities())
                .containsExactly("mcp:marketing-mcp-server:confirmMarketingCampaign");
        assertThat(properties.getApprovalCapabilities()).isEqualTo(
                Set.of("mcp:marketing-mcp-server:confirmMarketingCampaign"));
    }
}
