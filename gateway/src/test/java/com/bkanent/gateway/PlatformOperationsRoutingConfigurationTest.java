package com.bkanent.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformOperationsRoutingConfigurationTest {
    @Test
    void gatewayRouteAndAuthAudienceAgreeOnPlatformService() throws Exception {
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory"));
        var loader = new YamlPropertySourceLoader();
        var gateway = loader.load("gateway", new FileSystemResource(root.resolve("nacos/gateway.yaml"))).get(0);
        var auth = loader.load("auth", new FileSystemResource(root.resolve("nacos/auth-service.yaml"))).get(0);
        assertThat(gateway.getProperty("gateway.authorization.route-audiences.platform-operations-service"))
                .isEqualTo("platform-operations-service");
        String routeKey = null;
        for (int i = 0; i < 40; i++) {
            String prefix = "spring.cloud.gateway.server.webflux.routes[" + i + "]";
            if ("platform-operations-service".equals(gateway.getProperty(prefix + ".id"))) routeKey = prefix;
        }
        assertThat(routeKey).isNotNull();
        assertThat(gateway.getProperty(routeKey + ".uri")).isEqualTo("lb://platform-operations-service");
        assertThat(gateway.getProperty(routeKey + ".predicates[0]")).isEqualTo("Path=/platform-operations/**");
        assertThat(String.valueOf(auth.getProperty("auth.authorization.trusted-callers.gateway-current.audiences[3]")))
                .isEqualTo("platform-operations-service");
    }
}
