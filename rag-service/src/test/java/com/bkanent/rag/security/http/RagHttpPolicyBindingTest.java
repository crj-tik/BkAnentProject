package com.bkanent.rag.security.http;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static com.bkanent.rag.security.http.RagManagedEndpointPolicyTest.denied;

class RagHttpPolicyBindingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindingFixture.class);

    @Test
    void binderRetainsBracketedDottedHostMapWithListsAndEnvironmentPlaceholders() throws Exception {
        String yaml = """
                rag:
                  http:
                    mode: ${RAG_HTTP_MODE:PUBLIC_ONLY}
                    allowed-hosts: ${RAG_HTTP_ALLOWED_HOSTS:}
                    internal-networks:
                      '[host.docker.internal]':
                        - ${RAG_HTTP_HOST_NETWORK:}
                      '[elasticsearch]':
                        - 172.24.0.0/16
                """;
        var source = new YamlPropertySourceLoader().load("fixture-yaml",
                new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8))).get(0);
        runner.withInitializer(context -> {
            context.getEnvironment().getPropertySources().addFirst(source);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture-env", Map.of(
                    "RAG_HTTP_MODE", "MANAGED_INTERNAL",
                    "RAG_HTTP_ALLOWED_HOSTS", "host.docker.internal,elasticsearch,open-chatgpt.ke.com",
                    "RAG_HTTP_HOST_NETWORK", "192.168.65.254/32")));
        }).run(context -> {
            assertNull(context.getStartupFailure());
            var bound = Binder.get(context.getEnvironment()).bind("rag.http", Bindable.of(RagHttpPolicyConfiguration.class)).get();
            assertEquals(List.of("host.docker.internal", "elasticsearch", "open-chatgpt.ke.com"), bound.allowedHosts());
            assertEquals(Map.of("host.docker.internal", List.of("192.168.65.254/32"),
                    "elasticsearch", List.of("172.24.0.0/16")), bound.internalNetworks());
            var policy = context.getBean(RagEndpointPolicy.class);
            var dns = new RagPublicDnsResolver(policy, host -> new InetAddress[]{ip(192, 168, 65, 254)});
            assertArrayEquals(new byte[]{(byte) 192, (byte) 168, 65, (byte) 254}, dns.resolve("host.docker.internal")[0].getAddress());
            denied(() -> dns.resolve("elasticsearch"));
            policy.validateUrl("https://open-chatgpt.ke.com");
        });
    }

    @Test
    void applicationYamlDefaultsDoNotAssumeEitherRuntimeAndModeHostsUseEnv() throws Exception {
        var source = new YamlPropertySourceLoader().load("application-yaml", new ClassPathResource("application.yml")).get(0);
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(source)).run(context -> {
            var policy = context.getBean(RagEndpointPolicy.class);
            var bound = Binder.get(context.getEnvironment()).bind("rag.http", Bindable.of(RagHttpPolicyConfiguration.class)).get();
            assertEquals("PUBLIC_ONLY", bound.mode());
            assertTrue(bound.internalNetworks().isEmpty());
            denied(() -> policy.validateUrl("https://8.8.8.8"));
            denied(() -> policy.validateUrl("http://host.docker.internal:18119"));
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("live-http", Map.of(
                    "RAG_HTTP_MODE", "MANAGED_INTERNAL", "RAG_HTTP_ALLOWED_HOSTS", "host.docker.internal")));
            denied(() -> policy.validateUrl("http://host.docker.internal:18119"));
            var dns = new RagPublicDnsResolver(policy, host -> new InetAddress[]{ip(192, 168, 65, 254)});
            denied(() -> dns.resolve("host.docker.internal")); // no network guessed from this application file
            context.getEnvironment().getPropertySources().replace("live-http", new MapPropertySource("live-http", Map.of(
                    "RAG_HTTP_MODE", "MANAGED_INTERNAL", "RAG_HTTP_ALLOWED_HOSTS", "host.docker.internal",
                    "rag.http.internal-networks.[host.docker.internal][0]", "192.168.65.254/32")));
            assertEquals(ip(192, 168, 65, 254), dns.resolve("host.docker.internal")[0]);
        });
    }

    @Test
    void propertySourceReplacementChangesModeHostAndNetworkWithoutCachedAuthorization() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("live", Map.of(
                "rag.http.mode", "MANAGED_INTERNAL", "rag.http.allowed-hosts", "elasticsearch,host.docker.internal",
                "rag.http.internal-networks.[elasticsearch][0]", "172.24.0.0/16",
                "rag.http.internal-networks.[host.docker.internal][0]", "10.117.102.54/32"))))
                .run(context -> {
                    var policy = context.getBean(RagEndpointPolicy.class);
                    var dns = new RagPublicDnsResolver(policy, host -> new InetAddress[]{ip(172, 24, 0, 2)});
                    assertEquals(ip(172, 24, 0, 2), dns.resolve("elasticsearch")[0]);
                    context.getEnvironment().getPropertySources().replace("live", new MapPropertySource("live", Map.of(
                            "rag.http.mode", "MANAGED_INTERNAL", "rag.http.allowed-hosts", "elasticsearch",
                            "rag.http.internal-networks.[elasticsearch][0]", "10.0.0.0/8")));
                    denied(() -> dns.resolve("elasticsearch"));
                    denied(() -> policy.validateUrl("http://host.docker.internal"));
                    context.getEnvironment().getPropertySources().replace("live", new MapPropertySource("live", Map.of(
                            "rag.http.mode", "PUBLIC_ONLY", "rag.http.allowed-hosts", "elasticsearch",
                            "rag.http.internal-networks.[elasticsearch][0]", "172.24.0.0/16")));
                    denied(() -> dns.resolve("elasticsearch"));
                });
    }

    @Test
    void productionConfiguredPublicOnlyRequiresHostsBeforeDnsOrSocketConnection() {
        runner.withPropertyValues("rag.http.mode=PUBLIC_ONLY", "rag.http.allowed-hosts=").run(context -> {
            var policy = context.getBean(RagEndpointPolicy.class); // actual transport configuration factory
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var dns = new RagPublicDnsResolver(policy, host -> {
                calls.incrementAndGet();
                return new InetAddress[]{ip(8, 8, 8, 8)};
            });
            denied(() -> policy.validateUrl("https://public.example"));
            denied(() -> policy.validateUrl("https://8.8.8.8"));
            denied(() -> dns.resolve("public.example"));
            assertEquals(0, calls.get());
            var delegate = org.mockito.Mockito.mock(org.apache.hc.client5.http.io.HttpClientConnectionOperator.class);
            var operator = new RagConnectionOperator(policy, delegate);
            denied(() -> operator.connect(org.mockito.Mockito.mock(org.apache.hc.client5.http.io.ManagedHttpClientConnection.class),
                    new org.apache.hc.core5.http.HttpHost("http", "public.example", 80), null,
                    org.apache.hc.core5.util.Timeout.ofSeconds(5), org.apache.hc.core5.http.io.SocketConfig.custom().build(),
                    new org.apache.hc.core5.http.protocol.BasicHttpContext()));
            org.mockito.Mockito.verifyNoInteractions(delegate);
        });
        runner.run(context -> denied(() -> context.getBean(RagEndpointPolicy.class).validateHost("public.example")));
        assertDoesNotThrow(() -> new RagEndpointPolicy().validateUrl("https://8.8.8.8")); // legacy seam only
    }

    @Test
    void indexedListBindingAndInvalidMapValuesFailClosedWithoutBinderDetails() {
        runner.withPropertyValues("rag.http.mode=MANAGED_INTERNAL", "rag.http.allowed-hosts[0]=elasticsearch",
                        "rag.http.internal-networks.[elasticsearch][0]=172.24.0.0/16")
                .run(context -> {
                    var policy = context.getBean(RagEndpointPolicy.class);
                    policy.validateUrl("http://elasticsearch:9200");
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("invalid", Map.of(
                            "rag.http.internal-networks.[elasticsearch][0]", "secret-invalid-cidr")));
                    denied(() -> policy.validateUrl("http://elasticsearch:9200"));
                });
        runner.withPropertyValues("rag.http.mode=MANAGED_INTERNAL", "rag.http.allowed-hosts=elasticsearch",
                        "rag.http.internal-networks.[elasticsearch]=")
                .run(context -> denied(() -> context.getBean(RagEndpointPolicy.class).validateHost("elasticsearch")));
    }

    private static InetAddress ip(int a, int b, int c, int d) throws java.net.UnknownHostException {
        return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
    }

    @Configuration(proxyBeanMethods = false)
    static class BindingFixture {
        @Bean
        RagEndpointPolicy policy(Environment environment) {
            return new RagHttpTransportConfiguration().ragEndpointPolicy(environment);
        }
    }
}
