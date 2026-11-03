package com.bkanent.marketing.campaign;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingAuthorizationConfigurationTest {
    @Test
    void composeApplicationAndNacosShareTheExactMcpAuthorizationContract() throws Exception {
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory"));
        var loader = new YamlPropertySourceLoader();
        PropertySource<?> compose = load(loader, root.resolve("docker-compose.yml"));
        Map<String, String> tools = Map.of(
                "createMarketingContentDraft", "marketing.content.create.own",
                "prepareMarketingCampaign", "marketing.campaign.prepare",
                "confirmMarketingCampaign", "marketing.campaign.publish",
                "getMarketingCampaignReceipt", "marketing.campaign.read",
                "reconcileMarketingCampaignAttempt", "marketing.campaign.effects.read");

        assertThat(compose.getProperty("services.marketing-content-service.environment.MARKETING_AUTHORIZATION_ENABLED"))
                .isEqualTo("${MARKETING_AUTHORIZATION_ENABLED:-false}");
        assertThat(compose.getProperty("services.marketing-content-service.environment.MARKETING_MCP_AUTHORIZATION_ENABLED"))
                .isEqualTo("${MARKETING_MCP_AUTHORIZATION_ENABLED:-false}");
        for (Path path : new Path[]{root.resolve("nacos/marketing-content-service.yaml"),
                root.resolve("marketing-content-service/src/main/resources/application.yml")}) {
            PropertySource<?> config = load(loader, path);
            assertThat(config.getProperty("marketing.authorization.enabled"))
                    .isEqualTo("${MARKETING_AUTHORIZATION_ENABLED:false}");
            assertThat(config.getProperty("mcp.authorization.server.enabled"))
                    .isEqualTo("${MARKETING_MCP_AUTHORIZATION_ENABLED:false}");
            tools.forEach((tool, operation) -> assertThat(config.getProperty(
                    "mcp.authorization.server.tool-operations." + tool)).isEqualTo(operation));
            assertThat(config.getProperty("mcp.authorization.server.public-tools")).isEqualTo("");
        }
    }

    private static PropertySource<?> load(YamlPropertySourceLoader loader, Path path) throws Exception {
        return loader.load(path.toString(), new FileSystemResource(path)).get(0);
    }
}
