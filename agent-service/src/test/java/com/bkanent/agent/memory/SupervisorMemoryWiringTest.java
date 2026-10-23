package com.bkanent.agent.memory;

import com.bkanent.common.a2a.A2aMemoryAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SupervisorMemoryWiringTest {
    @Test
    void supervisorKeepsItsOwnMemoryBeansWhenSharedA2aConfigurationIsPresent() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("memory-identity-test",
                    Map.of("agent.memory-v2.private-key-file", "/run/secrets/service-identity.pem",
                            "a2a.memory.private-key-file", "/run/secrets/service-identity.pem",
                            "a2a.memory.key-id", "agent-service-current")));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(SupervisorMemoryJournal.class, () -> mock(SupervisorMemoryJournal.class));
            context.registerBean(com.bkanent.agent.orchestration.OrchestrationStore.class,
                    () -> mock(com.bkanent.agent.orchestration.OrchestrationStore.class));
            context.registerBean(com.bkanent.agent.security.AgentRagCredentialLeaseStore.class,
                    () -> mock(com.bkanent.agent.security.AgentRagCredentialLeaseStore.class));
            context.registerBean(com.bkanent.agent.orchestration.SupervisorModelTurn.class,
                    () -> mock(com.bkanent.agent.orchestration.SupervisorModelTurn.class));
            context.register(SupervisorMemoryConfiguration.class, A2aMemoryAutoConfiguration.class,
                    SupervisorMemoryLifecycle.class);
            context.refresh();

            var lifecycle = context.getBean(SupervisorMemoryLifecycle.class);
            assertThat(ReflectionTestUtils.getField(lifecycle, "reference"))
                    .isSameAs(context.getBean("supervisorMemoryReference"));
            assertThat(ReflectionTestUtils.getField(lifecycle, "cache"))
                    .isSameAs(context.getBean("supervisorMemoryCache"));
            assertThat(context.getBean("a2aMemoryHookFactory")).isInstanceOf(com.bkanent.common.a2a.A2aMemoryHookFactory.class);
        }
    }
}
