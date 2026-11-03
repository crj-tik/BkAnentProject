package com.bkanent.rag.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagVersionedDeploymentConfigurationTest {
    @Test
    void composeAndBothConfigurationLayersUseOneVersionedProtocolSwitch() throws Exception {
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory"));
        var loader = new YamlPropertySourceLoader();
        PropertySource<?> compose = loader.load("compose", new FileSystemResource(root.resolve("docker-compose.yml"))).get(0);
        Map<String, String[]> settings = Map.of(
                "agent-service", new String[]{"AGENT_RAG_VERSIONED_AUTHORIZATION_REQUIRED", "agent.rag-consumer.versioned-authorization-required"},
                "listing-master-service", new String[]{"LISTING_RAG_VERSIONED_AUTHORIZATION_REQUIRED", "listing.rag.versioned-authorization-required"},
                "memory-service", new String[]{"MEMORY_RAG_VERSIONED_AUTHORIZATION_REQUIRED", "memory.rag.versioned-authorization-required"},
                "rag-service", new String[]{"RAG_VERSIONED_AUTHORIZATION_ENABLED", "rag.security.versioned-authorization-enabled"});
        settings.forEach((service, values) -> {
            assertThat(compose.getProperty("services." + service + ".environment." + values[0]))
                    .as("Compose switch for %s", service).isEqualTo("${RAG_VERSIONED_AUTHORIZATION_ENABLED:-false}");
            try {
                for (Path path : new Path[]{root.resolve("nacos/" + service + ".yaml"),
                        root.resolve(service + "/src/main/resources/application.yml")}) {
                    PropertySource<?> config = loader.load(path.toString(), new FileSystemResource(path)).get(0);
                    assertThat(config.getProperty(values[1])).as("%s in %s", values[1], path)
                            .isEqualTo("${" + values[0] + ":false}");
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        assertThat(compose.getProperty("services.rag-service.env_file[0]"))
                .isEqualTo("./.secrets/service-identities/public-keys.env");
        for (Path path : new Path[]{root.resolve("nacos/rag-service.yaml"),
                root.resolve("rag-service/src/main/resources/application.yml")}) {
            PropertySource<?> config = loader.load(path.toString(), new FileSystemResource(path)).get(0);
            assertThat(config.getProperty("rag.security.authz-authority-public-keys.auth-service-current"))
                    .isEqualTo("${AUTH_SERVICE_IDENTITY_PUBLIC_KEY:}");
        }
    }
}
