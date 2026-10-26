package com.bkanent.memory.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MemoryV2DeploymentConfigurationTest {
    private static final Map<String, CallerIdentity> OFFICIAL_CALLERS = Map.ofEntries(
            Map.entry("agent-service", new CallerIdentity("agent-service-current", "AGENT_SERVICE_IDENTITY_PUBLIC_KEY", "supervisor", "supervisor-agent")),
            Map.entry("marketing-content-service", new CallerIdentity("marketing-content-service-current", "MARKETING_CONTENT_SERVICE_IDENTITY_PUBLIC_KEY", "marketing", "marketing-agent")),
            Map.entry("interview-service", new CallerIdentity("interview-service-current", "INTERVIEW_SERVICE_IDENTITY_PUBLIC_KEY", "interview", "interview-agent")),
            Map.entry("listing-master-service", new CallerIdentity("listing-master-service-current", "LISTING_MASTER_SERVICE_IDENTITY_PUBLIC_KEY", "listing", "listing-agent")),
            Map.entry("business-service", new CallerIdentity("business-service-current", "BUSINESS_SERVICE_IDENTITY_PUBLIC_KEY", "trade", "trade-agent")),
            Map.entry("contract-service", new CallerIdentity("contract-service-current", "CONTRACT_SERVICE_IDENTITY_PUBLIC_KEY", "contract", "contract-agent")),
            Map.entry("compare-engine-service", new CallerIdentity("compare-engine-service-current", "COMPARE_ENGINE_SERVICE_IDENTITY_PUBLIC_KEY", "compare", "compare-agent")),
            Map.entry("settlement-service", new CallerIdentity("settlement-service-current", "SETTLEMENT_SERVICE_IDENTITY_PUBLIC_KEY", "settlement", "settlement-agent")),
            Map.entry("notification-service", new CallerIdentity("notification-service-current", "NOTIFICATION_SERVICE_IDENTITY_PUBLIC_KEY", "notification", "notification-agent")),
            Map.entry("media-worker-service", new CallerIdentity("media-worker-service-current", "MEDIA_WORKER_SERVICE_IDENTITY_PUBLIC_KEY", "media", "media-agent")));

    @Test void allOfficialCallersHaveOneEd25519PublicKeyAndPreciseDomainAgentGrant() throws Exception {
        var resources = Set.of(new ClassPathResource("application.yml"),
                new FileSystemResource(Path.of(System.getProperty("maven.multiModuleProjectDirectory"), "nacos", "memory-service.yaml")));
        var publicKeys = new HashMap<String, Object>();
        OFFICIAL_CALLERS.values().forEach(identity -> publicKeys.put(identity.environmentKey(), "fixture-public-key"));

        for (Resource resource : resources) {
            for (var entry : OFFICIAL_CALLERS.entrySet()) {
                var caller = bindCaller(resource, entry.getKey(), publicKeys);
                var identity = entry.getValue();
                assertEquals(Map.of(identity.keyId(), "fixture-public-key"), caller.getPublicKeys(), entry.getKey());
                assertTrue(caller.isServiceDelivery(), entry.getKey() + " must be allowed to deliver its turn");
                assertEquals(Set.of(identity.agentId()), caller.getAllowedAgentsByDomain().get(identity.domain()));
                assertEquals(1, caller.getAllowedAgentsByDomain().size(), "no cross-domain grant for " + entry.getKey());
            }
        }
    }

    @Test void domainOutcomePublisherFlagsRemainIndependentAndDefaultOff() throws Exception {
        var resources = Set.of(new ClassPathResource("application.yml"),
                new FileSystemResource(Path.of(System.getProperty("maven.multiModuleProjectDirectory"),
                        "nacos", "memory-service.yaml")));
        Map<String, String> callerFlags = Map.of(
                "marketing-content-service", "MARKETING_MEMORY_EXPERIENCE_OUTCOME_PUBLISHER",
                "interview-service", "INTERVIEW_MEMORY_EXPERIENCE_OUTCOME_PUBLISHER",
                "listing-master-service", "LISTING_MEMORY_EXPERIENCE_OUTCOME_PUBLISHER",
                "business-service", "BUSINESS_MEMORY_EXPERIENCE_OUTCOME_PUBLISHER",
                "contract-service", "CONTRACT_MEMORY_EXPERIENCE_OUTCOME_PUBLISHER",
                "compare-engine-service", "COMPARE_MEMORY_EXPERIENCE_OUTCOME_PUBLISHER",
                "settlement-service", "SETTLEMENT_MEMORY_EXPERIENCE_OUTCOME_PUBLISHER");

        for (Resource resource : resources) {
            for (String callerName : callerFlags.keySet()) {
                assertFalse(bindCaller(resource, callerName, Map.of()).isExperienceOutcomePublisher(), callerName);
            }
            var values = Map.<String, Object>of("MARKETING_MEMORY_EXPERIENCE_OUTCOME_PUBLISHER", "true");
            assertTrue(bindCaller(resource, "marketing-content-service", values).isExperienceOutcomePublisher());
            assertFalse(bindCaller(resource, "agent-service", values).isExperienceOutcomePublisher());
        }
    }

    @Test void signedAuthorizationContextRolloutDefaultsOffAndUsesTheAuthAuthorityKey() throws Exception {
        var resources = Set.of(new ClassPathResource("application.yml"),
                new FileSystemResource(Path.of(System.getProperty("maven.multiModuleProjectDirectory"),
                        "nacos", "memory-service.yaml")));
        for (Resource resource : resources) {
            var properties = bindProperties(resource, Map.of("AUTH_SERVICE_IDENTITY_PUBLIC_KEY", "fixture-auth-public-key"));
            assertFalse(properties.isAuthorizationContextEnabled());
            assertEquals(Map.of("auth-service-current", "fixture-auth-public-key"),
                    properties.getAuthzAuthorityPublicKeys());
        }
    }

    private MemoryV2SecurityProperties.TrustedCaller bindCaller(Resource resource, String callerName,
            Map<String, ?> deploymentValues) throws Exception {
        PropertySource<?> yaml = new YamlPropertySourceLoader().load("memory-v2-test", resource).get(0);
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(yaml);
        var deploymentProperties = new HashMap<String, Object>();
        deploymentProperties.putAll(deploymentValues);
        environment.getPropertySources().addFirst(new MapPropertySource("deployment-test", deploymentProperties));
        var sources = ConfigurationPropertySources.get(environment);
        var placeholders = new PropertySourcesPlaceholdersResolver(environment);
        return new Binder(sources, placeholders)
                .bind("memory.v2.trusted-callers." + callerName,
                        Bindable.of(MemoryV2SecurityProperties.TrustedCaller.class))
                .orElseThrow(IllegalStateException::new);
    }

    private MemoryV2SecurityProperties bindProperties(Resource resource, Map<String, ?> deploymentValues) throws Exception {
        PropertySource<?> yaml = new YamlPropertySourceLoader().load("memory-v2-test", resource).get(0);
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(yaml);
        environment.getPropertySources().addFirst(new MapPropertySource("deployment-test", new HashMap<>(deploymentValues)));
        var sources = ConfigurationPropertySources.get(environment);
        var placeholders = new PropertySourcesPlaceholdersResolver(environment);
        return new Binder(sources, placeholders).bind("memory.v2", Bindable.of(MemoryV2SecurityProperties.class))
                .orElseThrow(IllegalStateException::new);
    }

    private record CallerIdentity(String keyId, String environmentKey, String domain, String agentId) { }
}
