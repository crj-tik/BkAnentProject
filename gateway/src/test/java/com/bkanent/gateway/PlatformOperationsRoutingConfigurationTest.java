package com.bkanent.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformOperationsRoutingConfigurationTest {
    @Test
    void everyConfiguredRouteHasExactAudienceTrustedByAuth() throws Exception {
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory"));
        var loader = new YamlPropertySourceLoader();
        var gateway = loader.load("gateway", new FileSystemResource(root.resolve("nacos/gateway.yaml"))).get(0);
        var auth = loader.load("auth", new FileSystemResource(root.resolve("nacos/auth-service.yaml"))).get(0);
        Map<String, String> expected = Map.ofEntries(
                Map.entry("auth-service", "auth-service"), Map.entry("agent-service", "agent-service"),
                Map.entry("listing-master-service", "listing-master-service"),
                Map.entry("listing-comparison-compatibility", "listing-master-service"),
                Map.entry("customer-service", "customer-service"),
                Map.entry("platform-operations-service", "platform-operations-service"),
                Map.entry("marketing-promotion-compat", "marketing-content-service"),
                Map.entry("marketing-content-service", "marketing-content-service"),
                Map.entry("notification-service", "notification-service"),
                Map.entry("interview-service", "interview-service"));
        Set<String> actualRoutes = new HashSet<>();
        Set<String> trustedAudiences = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            Object audience = auth.getProperty("auth.authorization.trusted-callers.gateway-current.audiences[" + i + "]");
            if (audience != null) trustedAudiences.add(audience.toString());
            String prefix = "spring.cloud.gateway.server.webflux.routes[" + i + "]";
            Object route = gateway.getProperty(prefix + ".id");
            if (route == null) continue;
            String id = route.toString();
            assertThat(actualRoutes.add(id)).as("duplicate route %s", id).isTrue();
            assertThat(expected).as("route must have a reviewed target: %s", id).containsKey(id);
            String target = expected.get(id);
            assertThat(gateway.getProperty("gateway.authorization.route-audiences." + id)).isEqualTo(target);
            if (!"auth-service".equals(id)) {
                assertThat(gateway.getProperty(prefix + ".uri")).isEqualTo("lb://" + target);
            }
        }
        assertThat(actualRoutes).containsExactlyInAnyOrderElementsOf(expected.keySet());
        assertThat(trustedAudiences).containsExactlyInAnyOrderElementsOf(new HashSet<>(expected.values()));
        assertThat(auth.getProperty("auth.authorization.trusted-callers.agent-service-current.operations[0]"))
                .isEqualTo("BIND_EXECUTION");
        assertThat(auth.getProperty("auth.authorization.trusted-callers.agent-service-current.operations[1]"))
                .isEqualTo("REFRESH_AUTHORIZATION");
        var agent = loader.load("agent", new FileSystemResource(root.resolve("nacos/agent-service.yaml"))).get(0);
        var compose = loader.load("compose", new FileSystemResource(root.resolve("docker-compose.yml"))).get(0);
        assertThat(agent.getProperty("agent.authorization.caller-service"))
                .isEqualTo(auth.getProperty("auth.authorization.trusted-callers.agent-service-current.service"));
        assertThat(agent.getProperty("agent.authorization.audience"))
                .isEqualTo(auth.getProperty("auth.authorization.trusted-callers.agent-service-current.audiences[0]"));
        assertThat(agent.getProperty("agent.authorization.public-key"))
                .isEqualTo(auth.getProperty("auth.authorization.trusted-callers.agent-service-current.public-key"));
        assertThat(compose.getProperty("services.agent-service.environment.SERVICE_IDENTITY_KEY_ID"))
                .isEqualTo("agent-service-current");
        assertThat(agent.getProperty("agent.authorization.key-id"))
                .isEqualTo("${SERVICE_IDENTITY_KEY_ID:agent-service-current}");
    }

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
        for (int i = 0; i < 40; i++) {
            String prefix = "spring.cloud.gateway.server.webflux.routes[" + i + "]";
            assertThat(gateway.getProperty(prefix + ".id")).isNotEqualTo("contract-service").isNotEqualTo("settlement-service");
            assertThat(gateway.getProperty(prefix + ".predicates[0]")).isNotEqualTo("Path=/contracts/**")
                    .isNotEqualTo("Path=/settlements/**");
        }
        assertThat(String.valueOf(auth.getProperty("auth.authorization.trusted-callers.gateway-current.audiences[3]")))
                .isEqualTo("platform-operations-service");
    }
}
