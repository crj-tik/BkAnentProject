package com.bkanent.agent.registry;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OfficialA2aProviderConfigurationTest {

    @Test
    void allDomainOfficialA2aProvidersRemainPresent() {
        String[] providers = {
                "business-service/src/main/java/com/bkanent/business/a2a/TradeOfficialA2aAgent.java",
                "listing-master-service/src/main/java/com/bkanent/listing/a2a/ListingOfficialA2aAgent.java",
                "marketing-content-service/src/main/java/com/bkanent/marketing/a2a/MarketingOfficialA2aAgent.java",
                "media-worker-service/src/main/java/com/bkanent/media/a2a/MediaOfficialA2aAgent.java",
                "contract-service/src/main/java/com/bkanent/contract/a2a/ContractOfficialA2aAgent.java",
                "settlement-service/src/main/java/com/bkanent/settlement/a2a/SettlementOfficialA2aAgent.java",
                "notification-service/src/main/java/com/bkanent/notification/a2a/NotificationOfficialA2aAgent.java",
                "compare-engine-service/src/main/java/com/bkanent/compare/a2a/CompareOfficialA2aAgent.java"
        };

        Path root = locateProjectRoot();
        for (String provider : providers) {
            assertThat(Files.exists(root.resolve(provider)))
                    .withFailMessage("missing official A2A provider: " + provider)
                    .isTrue();
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
