package com.bkanent.agent.registry;

import com.bkanent.agent.config.DistributedAgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OfficialA2aRegistrationValidatorTest {

    @Test
    void reportsCustomRuntimeAndNonOfficialCardPath() {
        DistributedAgentProperties.AgentRegistration registration = new DistributedAgentProperties.AgentRegistration();
        registration.setAgentId("legacy-agent");
        registration.setRuntimeProvider("custom");
        registration.setBaseUrl("http://localhost:8080");
        registration.setAgentCardPath("/internal/agent-card");

        List<String> errors = OfficialA2aRegistrationValidator.validate(registration);

        assertThat(errors).anyMatch(error -> error.contains("custom HTTP runtime"));
        assertThat(errors).anyMatch(error -> error.contains("official /.well-known/agent.json"));
    }

    @Test
    void acceptsOfficialRegistration() {
        DistributedAgentProperties.AgentRegistration registration = new DistributedAgentProperties.AgentRegistration();
        registration.setAgentId("listing-agent");
        registration.setRuntimeProvider("official");
        registration.setBaseUrl("http://localhost:8080");
        registration.setAgentCardPath("/.well-known/agent.json");

        assertThat(OfficialA2aRegistrationValidator.validate(registration)).isEmpty();
    }
}
