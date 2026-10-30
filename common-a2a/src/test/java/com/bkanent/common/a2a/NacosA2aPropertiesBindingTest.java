package com.bkanent.common.a2a;

import com.alibaba.cloud.ai.a2a.registry.nacos.properties.NacosA2aProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NacosA2aPropertiesBindingTest {
    @Test
    void resolvesA2aMaintainerCredentialsFromComposeEnvironmentPlaceholders() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("nacos-template", Map.of(
                "spring.ai.alibaba.a2a.nacos.server-addr", "${NACOS_SERVER:127.0.0.1:8848}",
                "spring.ai.alibaba.a2a.nacos.username", "${NACOS_USERNAME:}",
                "spring.ai.alibaba.a2a.nacos.password", "${NACOS_PASSWORD:}",
                "NACOS_SERVER", "nacos:8848",
                "NACOS_USERNAME", "bkagent",
                "NACOS_PASSWORD", "fixture-password")));

        var properties = Binder.get(environment)
                .bind(NacosA2aProperties.PREFIX, Bindable.of(NacosA2aProperties.class))
                .orElseThrow(IllegalStateException::new);
        properties.setEnvironment(environment);

        var resolved = properties.getNacosProperties();

        assertThat(resolved.getProperty("serverAddr")).isEqualTo("nacos:8848");
        assertThat(resolved.getProperty("username")).isEqualTo("bkagent");
        assertThat(resolved.getProperty("password")).isEqualTo("fixture-password");
    }

    @Test
    void maintainerClientUsesComposeCredentialsWhenNacosTemplateFieldsAreUnbound() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("compose", Map.of(
                "NACOS_SERVER", "nacos:8848",
                "NACOS_NAMESPACE", "BK-AGENT",
                "NACOS_USERNAME", "bkagent",
                "NACOS_PASSWORD", "fixture-password")));

        var resolved = SkillNacosCompatibilityAutoConfiguration.resolveMaintainerProperties(
                new NacosA2aProperties(), environment);

        assertThat(resolved.getProperty("serverAddr")).isEqualTo("nacos:8848");
        assertThat(resolved.getProperty("namespace")).isEqualTo("BK-AGENT");
        assertThat(resolved.getProperty("username")).isEqualTo("bkagent");
        assertThat(resolved.getProperty("password")).isEqualTo("fixture-password");
    }
}
