package com.bkanent.common.a2a;

import com.bkanent.common.rpc.RagIndexRpcService;
import com.bkanent.common.rpc.RagRetrievalRpcService;
import org.apache.dubbo.common.ProtocolServiceKey;
import org.apache.dubbo.config.AbstractConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.registry.client.DefaultServiceInstance;
import org.apache.dubbo.registry.client.metadata.SpringCloudServiceInstanceNotificationCustomizer;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Offline official-adapter and YAML binding checks; no context startup, registry, export or RPC/HTTP call. */
class DubboConsumerProtocolConfigurationTest {
    @Test
    void springCloudOnlyHttpMetadataMatchesUnconstrainedConsumerButNotBoundDubboConsumer() throws Exception {
        FrameworkModel framework = new FrameworkModel();
        try {
            var application = framework.newApplication();
            ModuleModel module = application.getDefaultModule();
            var http = new DefaultServiceInstance("rag-service", "127.0.0.1", 9018, application);
            http.setMetadata(new HashMap<>(Map.of("preserved.register.source", "SPRING_CLOUD")));
            assertFalse(http.getMetadata().containsKey("dubbo.endpoints"));
            new SpringCloudServiceInstanceNotificationCustomizer().customize(List.of(http));
            var metadata = http.getServiceMetadata();
            assertNotNull(metadata);
            assertEquals("rest", metadata.getServices().get("*").getProtocol());
            assertEquals(9018, metadata.getServices().get("*").getPort());

            ConsumerConfig unconstrained = new ConsumerConfig(module);
            assertNull(unconstrained.getProtocol());
            MockEnvironment environment = resourceEnvironment("listing-master-service");
            ConsumerConfig constrained = bind(environment, module);
            Map<String, String> consumerParameters = new HashMap<>();
            AbstractConfig.appendParameters(consumerParameters, constrained);
            assertEquals("dubbo", consumerParameters.get("protocol"));

            for (Class<?> service : List.of(RagRetrievalRpcService.class, RagIndexRpcService.class)) {
                var wildcardKey = new ProtocolServiceKey(service.getName(), null, null, unconstrained.getProtocol());
                var wildcardMatches = metadata.getMatchedServiceInfos(wildcardKey);
                assertEquals(1, wildcardMatches.size());
                assertEquals(service.getName(), wildcardMatches.get(0).getName());
                assertEquals("rest", wildcardMatches.get(0).getProtocol());
                assertEquals(9018, wildcardMatches.get(0).getPort());

                var dubboKey = new ProtocolServiceKey(service.getName(), null, null, consumerParameters.get("protocol"));
                assertTrue(metadata.getMatchedServiceInfos(dubboKey).isEmpty());
                // REST adaptation itself remains available for a consumer that explicitly requests REST.
                assertFalse(metadata.getMatchedServiceInfos(
                        new ProtocolServiceKey(service.getName(), null, null, "rest")).isEmpty());
            }
        } finally {
            framework.destroy();
        }
    }

    @ParameterizedTest(name = "{0}: application and Nacos overlay bind a pure Dubbo consumer")
    @ValueSource(strings = {"agent-service", "business-service", "contract-service",
            "customer-service", "gateway", "listing-master-service", "marketing-content-service",
            "media-worker-service", "memory-service", "notification-service",
            "rag-service", "settlement-service"})
    void actualResourceAndHigherPriorityNacosTemplateBindOfficialConsumerProtocol(String service) throws Exception {
        FrameworkModel framework = new FrameworkModel();
        try {
            ModuleModel module = framework.newApplication().getDefaultModule();
            MockEnvironment environment = resourceEnvironment(service);
            assertConsumerDefaults(service, bind(environment, module));
            Path template = repository().resolve("nacos/" + service + ".yaml");
            assertTrue(Files.isRegularFile(template), service + " matching Nacos template must be checked");
            addYaml(environment, template);
            assertConsumerDefaults(service, bind(environment, module));
        } finally {
            framework.destroy();
        }
    }

    private static void assertConsumerDefaults(String service, ConsumerConfig consumer) {
        assertEquals("dubbo", consumer.getProtocol(), service + " must reject HTTP/REST RPC candidates");
        Map<String, String> parameters = new HashMap<>();
        AbstractConfig.appendParameters(parameters, consumer);
        assertEquals("dubbo", parameters.get("protocol"));
        if (List.of("memory-service", "rag-service").contains(service)) {
            assertEquals(Boolean.FALSE, consumer.isCheck());
            assertEquals(Boolean.FALSE, consumer.isInit());
            assertEquals(0, consumer.getRetries());
        }
        if ("rag-service".equals(service)) {
            assertEquals(5000, consumer.getTimeout());
        }
    }

    private static ConsumerConfig bind(MockEnvironment environment, ModuleModel module) {
        return Binder.get(environment).bind("dubbo.consumer", Bindable.ofInstance(new ConsumerConfig(module))).get();
    }

    private static MockEnvironment resourceEnvironment(String service) throws Exception {
        // MockEnvironment deliberately excludes process environment and system properties, including secrets.
        MockEnvironment environment = new MockEnvironment();
        addYaml(environment, repository().resolve(service + "/src/main/resources/application.yml"));
        return environment;
    }

    private static void addYaml(MockEnvironment environment, Path file) throws Exception {
        var sources = new YamlPropertySourceLoader().load(file.toString(), new FileSystemResource(file));
        for (var source : sources) {
            environment.getPropertySources().addFirst(source);
        }
    }

    private static Path repository() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("common-a2a/pom.xml"))) {
                return path;
            }
        }
        throw new AssertionError("Run from the repository or common-a2a module");
    }
}
