package com.bkanent.agent.registry;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OfficialA2aProviderConfigurationTest {

    @Test
    void allDomainOfficialA2aProvidersRemainPresent() throws Exception {
        String[] providers = {
                "listing-master-service/src/main/java/com/bkanent/listing/a2a/ListingOfficialA2aAgent.java",
                "marketing-content-service/src/main/java/com/bkanent/marketing/a2a/MarketingOfficialA2aAgent.java",
                "media-worker-service/src/main/java/com/bkanent/media/a2a/MediaOfficialA2aAgent.java",
                "notification-service/src/main/java/com/bkanent/notification/a2a/NotificationOfficialA2aAgent.java"
        };

        Path root = locateProjectRoot();
        for (String provider : providers) {
            assertThat(Files.exists(root.resolve(provider)))
                    .withFailMessage("missing official A2A provider: " + provider)
                    .isTrue();
            assertThat(Files.readString(root.resolve(provider)))
                    .contains("new A2aSupervisorContextInterceptor()")
                    .contains("OfficialA2aAgentExecutor")
                    .contains("A2aOutputPolicy.structured(");
        }
    }

    @Test
    void everyProviderHasSupervisorContextInterceptor() {
        String[] interceptors = {
                "listing-master-service/src/main/java/com/bkanent/listing/a2a/A2aSupervisorContextInterceptor.java",
                "marketing-content-service/src/main/java/com/bkanent/marketing/a2a/A2aSupervisorContextInterceptor.java",
                "media-worker-service/src/main/java/com/bkanent/media/a2a/A2aSupervisorContextInterceptor.java",
                "notification-service/src/main/java/com/bkanent/notification/a2a/A2aSupervisorContextInterceptor.java"
        };
        Path root = locateProjectRoot();
        for (String interceptor : interceptors) {
            assertThat(Files.exists(root.resolve(interceptor)))
                    .withFailMessage("missing Supervisor context interceptor: " + interceptor)
                    .isTrue();
        }
    }

    @Test
    void everyProviderAdvertisesOfficialCardAndEndpoint() throws Exception {
        String[] configurations = {
                "nacos/listing-master-service.yaml",
                "nacos/marketing-content-service.yaml",
                "nacos/media-worker-service.yaml",
                "nacos/notification-service.yaml"
        };
        Path root = locateProjectRoot();
        for (String configuration : configurations) {
            String content = Files.readString(root.resolve(configuration));
            assertThat(content)
                    .contains("enabled: true")
                    .contains("message-url: /a2a")
                    .contains("agent-card-url: /.well-known/agent.json")
                    .contains("agent-runtime-provider: official")
                    .contains("agent-card-path: /.well-known/agent.json")
                    .contains("a2a-path: /a2a")
                    .contains("streaming: true")
                    .contains("default-input-modes: text,application/json")
                    .contains("default-output-modes: text,application/json")
                    .contains("nextHints")
                    .contains("contentType=");
        }
    }

    @Test
    void everyDomainServiceDependsOnSharedA2aExecutorModule() throws Exception {
        String[] poms = {
                "listing-master-service/pom.xml",
                "platform-operations-service/pom.xml",
                "marketing-content-service/pom.xml",
                "media-worker-service/pom.xml",
                "notification-service/pom.xml"
        };
        Path root = locateProjectRoot();
        for (String pom : poms) {
            assertThat(Files.readString(root.resolve(pom)))
                    .withFailMessage("missing common-a2a dependency: " + pom)
                    .contains("<artifactId>common-a2a</artifactId>");
        }
    }

    @Test
    void preConsolidationServiceSourcesAndTemplatesAreRetired() {
        Path root = locateProjectRoot();
        for (String retired : new String[]{"business-service", "business-review-service", "knowledge-service",
                "compare-engine-service", "contract-service", "settlement-service"}) {
            assertThat(Files.exists(root.resolve(retired).resolve("pom.xml")))
                    .as("legacy Maven module %s", retired).isFalse();
            assertThat(Files.exists(root.resolve(retired).resolve("src")))
                    .as("legacy source tree %s", retired).isFalse();
            assertThat(Files.exists(root.resolve("nacos/" + retired + ".yaml")))
                    .as("legacy Nacos template %s", retired).isFalse();
        }
    }

    private Path locateProjectRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("nacos"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("project root not found");
    }
}
