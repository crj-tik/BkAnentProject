package com.bkanent.common.skill.config;

import com.bkanent.common.skill.core.SkillFileLoader;
import com.bkanent.common.skill.core.SkillFileWatcher;
import com.bkanent.common.skill.core.SkillMatcher;
import com.bkanent.common.skill.core.SkillRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class SkillAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SkillAutoConfiguration.class));

    @Test
    void providesCoreBeansByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SkillFileLoader.class);
            assertThat(context).hasSingleBean(SkillRegistry.class);
            assertThat(context).hasSingleBean(SkillMatcher.class);
            assertThat(context).hasSingleBean(SkillFileWatcher.class);
            assertThat(context.getBean(SkillProperties.class).watchEnabled()).isTrue();
        });
    }

    @Test
    void bindsAgentSkillsProperties() {
        runner.withPropertyValues("agent.skills.external-dir=/tmp/skills", "agent.skills.watch-enabled=false")
                .run(context -> {
                    SkillProperties properties = context.getBean(SkillProperties.class);
                    assertThat(properties.externalDir()).isEqualTo("/tmp/skills");
                    assertThat(properties.watchEnabled()).isFalse();
                });
    }

    @Test
    void consumerDefinedBeansWinOverAutoConfiguration() {
        runner.withUserConfiguration(CustomRegistryConfig.class).run(context -> {
            assertThat(context).hasSingleBean(SkillRegistry.class);
            assertThat(context.getBean(SkillRegistry.class)).isSameAs(context.getBean("customRegistry"));
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomRegistryConfig {

        @Bean
        SkillRegistry customRegistry() {
            return new SkillRegistry(new SkillFileLoader(), "");
        }
    }
}
