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
    @Test void supervisorMemoryCallerIsExplicitlyScopedInApplicationAndNacosConfiguration() throws Exception {
        var deploymentValues = Map.<String, Object>of(
                "AGENT_MEMORY_V2_KEY_ID", "agent-memory-current",
                "AGENT_MEMORY_V2_SIGNING_SECRET", "");
        var application = bindCaller(new ClassPathResource("application.yml"), "agent-service", deploymentValues);
        var nacos = bindCaller(new FileSystemResource(Path.of(System.getProperty("maven.multiModuleProjectDirectory"),
                "nacos", "memory-service.yaml")), "agent-service", deploymentValues);

        for (var caller : Set.of(application, nacos)) {
            assertEquals("agent-memory-current", caller.getKeyId());
            assertEquals("", caller.getSigningSecret());
            assertTrue(caller.isServiceDelivery());
            assertEquals(Set.of("supervisor-agent"), caller.getAllowedAgentsByDomain().get("supervisor"));
            assertFalse(caller.isExperienceOutcomePublisher(), "Supervisor must not assert domain outcomes");
            assertFalse(caller.isExperienceGovernancePublisher());
        }
    }

    @Test void domainOutcomePublisherFlagsAreIndependentAndDefaultOffInApplicationAndNacosConfiguration() throws Exception {
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
            var values = new HashMap<String, Object>();
            callerFlags.values().forEach(key -> values.put(key, "false"));
            for (var entry : callerFlags.entrySet()) {
                var caller = bindCaller(resource, entry.getKey(), values);
                assertFalse(caller.isExperienceOutcomePublisher(), entry.getKey() + " should default off");
            }
            values.put("MARKETING_MEMORY_EXPERIENCE_OUTCOME_PUBLISHER", "true");
            assertTrue(bindCaller(resource, "marketing-content-service", values).isExperienceOutcomePublisher(),
                    "one domain must be independently switchable");
            assertFalse(bindCaller(resource, "agent-service", values).isExperienceOutcomePublisher(),
                    "Agent model output must not be trusted as a domain result");
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
}
