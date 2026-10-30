package com.bkanent.auth;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FourDomainCallerConfigurationTest {
    @Test
    void knowledgeAndReviewHaveOnlyResolveBeaconAccessAcrossBothConfigurations() throws Exception {
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory"));
        for (String filename : new String[]{"nacos/auth-service.yaml", "auth-service/src/main/resources/application.yml"}) {
            String yaml = Files.readString(root.resolve(filename)).replace("\r\n", "\n");
            for (String service : new String[]{"knowledge-service", "business-review-service", "platform-operations-service"}) {
                String key = service.replace('-', '_').toUpperCase() + "_IDENTITY_PUBLIC_KEY";
                assertTrue(yaml.contains(service + "-current:\n        caller-service: " + service
                        + "\n        public-key: ${" + key + ":}\n        allowed-operations: [RESOLVE_BEACON]"), filename);
            }
            assertFalse(yaml.contains("compare-engine-service-current:"), filename);
        }
    }
}
