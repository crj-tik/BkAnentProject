package com.bkanent.platform;

import com.bkanent.business.config.BusinessAuthorizationProperties;
import com.bkanent.businessreview.security.BusinessReviewAuthorizationProperties;
import com.bkanent.knowledge.config.KnowledgeAuthorizationProperties;
import com.bkanent.knowledge.config.KnowledgeDataSourceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformOperationsConfigurationTest {
    @Test
    void nacosTemplateBindsSeparateDatabaseAccountsAndAllIngressGuards() throws Exception {
        var path = Path.of(System.getProperty("maven.multiModuleProjectDirectory"), "nacos", "platform-operations-service.yaml");
        var yaml = new YamlPropertySourceLoader().load("platform", new FileSystemResource(path)).get(0);
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addLast(yaml);
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "PLATFORM_OPERATIONS_DB_USER", "app-test", "PLATFORM_OPERATIONS_DB_PASSWORD", "app-password",
                "PLATFORM_OPERATIONS_SOURCE_DB_USER", "source-test", "PLATFORM_OPERATIONS_SOURCE_DB_PASSWORD", "source-password",
                "AUTH_SERVICE_IDENTITY_PUBLIC_KEY", "test-public-key", "A2A_PUBLIC_BASE_URL", "http://localhost:9010")));
        var binder = new Binder(ConfigurationPropertySources.get(environment), new PropertySourcesPlaceholdersResolver(environment));
        var databases = binder.bind("knowledge.datasource", Bindable.of(KnowledgeDataSourceProperties.class)).orElseThrow(IllegalStateException::new);
        assertThat(databases.getPrimary().getUsername()).isEqualTo("app-test");
        assertThat(databases.getSource().getUsername()).isEqualTo("source-test");
        assertThat(databases.getPrimary().getUrl()).contains("/bk_platform_operations?");
        assertThat(databases.getSource().getUrl()).contains("/bk_platform_operations?");
        var business = binder.bind("business.authorization", Bindable.of(BusinessAuthorizationProperties.class)).orElseThrow(IllegalStateException::new);
        var review = binder.bind("business-review.authorization", Bindable.of(BusinessReviewAuthorizationProperties.class)).orElseThrow(IllegalStateException::new);
        var knowledge = binder.bind("knowledge.authorization", Bindable.of(KnowledgeAuthorizationProperties.class)).orElseThrow(IllegalStateException::new);
        assertThat(business.isEnabled()).isTrue();
        assertThat(review.isEnabled()).isTrue();
        assertThat(knowledge.isEnabled()).isTrue();
        assertThat(business.getAudience()).isEqualTo("platform-operations-service");
        assertThat(review.getAudience()).isEqualTo("platform-operations-service");
        assertThat(knowledge.getAudience()).isEqualTo("platform-operations-service");
        assertThat(business.getAuthorityPublicKeys()).containsEntry("auth-service-current", "test-public-key");
        assertThat(review.getAuthorityPublicKeys()).containsEntry("auth-service-current", "test-public-key");
        assertThat(knowledge.getAuthorityPublicKeys()).containsEntry("auth-service-current", "test-public-key");
        assertThat(environment.getProperty("spring.ai.alibaba.a2a.server.card.name")).isEqualTo("platform-operations-agent");
        assertThat(environment.getProperty("spring.ai.mcp.server.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("business-review.sources.knowledge-base-url")).isNull();
        assertThat(environment.getProperty("service.readiness.endpoints.memory")).isEqualTo("http://memory-service:9011");
    }
}
