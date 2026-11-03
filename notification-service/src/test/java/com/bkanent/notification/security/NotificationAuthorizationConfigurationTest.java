package com.bkanent.notification.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationAuthorizationConfigurationTest {
    @Test
    void composeApplicationAndNacosShareTheExactMcpAuthorizationContract() throws Exception {
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory"));
        var loader = new YamlPropertySourceLoader();
        PropertySource<?> compose = load(loader, root.resolve("docker-compose.yml"));
        Map<String, String> tools = Map.of(
                "sendStationMessage", "notification.message.send.all",
                "sendMyStationMessage", "notification.message.send.own",
                "sendEmailMessage", "notification.email.send.all",
                "listUserMessages", "notification.message.read.own",
                "countUnreadMessages", "notification.message.read.own");

        assertThat(compose.getProperty("services.notification-service.environment.NOTIFICATION_AUTHORIZATION_ENABLED"))
                .isEqualTo("${NOTIFICATION_AUTHORIZATION_ENABLED:-false}");
        assertThat(compose.getProperty("services.notification-service.environment.NOTIFICATION_MCP_AUTHORIZATION_ENABLED"))
                .isEqualTo("${NOTIFICATION_MCP_AUTHORIZATION_ENABLED:-false}");
        for (Path path : new Path[]{root.resolve("nacos/notification-service.yaml"),
                root.resolve("notification-service/src/main/resources/application.yml")}) {
            PropertySource<?> config = load(loader, path);
            assertThat(config.getProperty("notification.authorization.enabled"))
                    .isEqualTo("${NOTIFICATION_AUTHORIZATION_ENABLED:false}");
            assertThat(config.getProperty("notification.authorization.audience")).isEqualTo("notification-service");
            assertThat(config.getProperty("notification.authorization.authority-public-keys.auth-service-current"))
                    .isEqualTo("${AUTH_SERVICE_IDENTITY_PUBLIC_KEY:}");
            assertThat(config.getProperty("mcp.authorization.server.enabled"))
                    .isEqualTo("${NOTIFICATION_MCP_AUTHORIZATION_ENABLED:false}");
            tools.forEach((tool, operation) -> assertThat(config.getProperty(
                    "mcp.authorization.server.tool-operations." + tool)).isEqualTo(operation));
            assertThat(config.getProperty("mcp.authorization.server.public-tools")).isEqualTo("");
        }
    }

    private static PropertySource<?> load(YamlPropertySourceLoader loader, Path path) throws Exception {
        return loader.load(path.toString(), new FileSystemResource(path)).get(0);
    }
}
