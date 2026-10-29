package com.bkanent.businessreview;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessReviewConfigurationTest {
    @Test
    void listingSourceDefaultsMatchTheListingServicePortAcrossConfiguration() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String listing = Files.readString(root.resolve("listing-master-service/src/main/resources/application.yml"));
        var port = Pattern.compile("(?m)^server:\\s*\\R\\s+port:\\s*(\\d+)\\s*$").matcher(listing);
        assertThat(port.find()).as("Listing 服务端口").isTrue();
        String address = "http://listing-master-service:" + port.group(1);
        Map<String, String> expected = Map.of(
                ".env.example", "BUSINESS_REVIEW_LISTING_BASE_URL=" + address,
                "docker-compose.yml", "BUSINESS_REVIEW_LISTING_BASE_URL: ${BUSINESS_REVIEW_LISTING_BASE_URL:-" + address + "}",
                "nacos/business-review-service.yaml", "listing-base-url: ${BUSINESS_REVIEW_LISTING_BASE_URL:" + address + "}",
                "business-review-service/src/main/resources/application.yml", "listing-base-url: ${BUSINESS_REVIEW_LISTING_BASE_URL:" + address + "}"
        );
        for (var entry : expected.entrySet()) {
            assertThat(Files.readString(root.resolve(entry.getKey())))
                    .as(entry.getKey()).contains(entry.getValue());
        }
    }

    @Test
    void fullDeploymentDoesNotRequireRetiredCompareOrPromotionTemplates() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String initialization = Files.readString(root.resolve("scripts/deploy/init-environment.sh"));
        String fullRequirements = initialization.lines()
                .filter(line -> line.contains("required_configs=\"$required_configs agent-service.yaml"))
                .findFirst().orElseThrow();
        assertThat(fullRequirements).contains("business-review-service.yaml", "knowledge-service.yaml",
                        "listing-master-service.yaml", "marketing-content-service.yaml")
                .doesNotContain("compare-engine-service.yaml", "promotion-service.yaml");
    }
}
