package com.bkanent.rag.security.http;

import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.io.ManagedHttpClientConnection;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.protocol.BasicHttpContext;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagHttpTransportTest {
    private final RagEndpointPolicy policy = new RagEndpointPolicy();

    private static InetAddress ip(int a, int b, int c, int d) throws Exception {
        return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
    }

    @Test
    void allDnsAnswersCheckedAndReconnectionsDoNotReuseAValidatedAnswer() throws Exception {
        InetAddress publicIp = ip(8, 8, 8, 8);
        InetAddress privateIp = ip(10, 0, 0, 1);
        AtomicReference<InetAddress[]> answer = new AtomicReference<>(new InetAddress[]{publicIp});
        AtomicInteger calls = new AtomicInteger();
        RagPublicDnsResolver resolver = new RagPublicDnsResolver(policy, host -> {
            calls.incrementAndGet();
            return answer.get();
        });
        assertArrayEquals(new InetAddress[]{publicIp}, resolver.resolve("public.example"));
        answer.set(new InetAddress[]{publicIp, privateIp});
        assertThrows(RagException.class, () -> resolver.resolve("public.example"));
        assertEquals(2, calls.get());
        assertEquals("public.example", resolver.resolveCanonicalHostname("public.example"));
        assertEquals(2, calls.get());
        assertThrows(RagException.class, () -> new RagPublicDnsResolver(
                new RagEndpointPolicy(() -> Set.of("public.example")), host -> answer.get()).resolve("public.example"));
    }

    @Test
    void jdkDnsProviderPresentWithoutOpeningAContextOrLookingUpAnyHost() throws Exception {
        assertTrue(ModuleLayer.boot().findModule("java.naming").isPresent());
        assertTrue(ModuleLayer.boot().findModule("jdk.naming.dns").isPresent());
        assertNotNull(Class.forName("com.sun.jndi.dns.DnsContextFactory"));
    }

    @Test
    void rawDnsRecordsPreserveAndRejectMappedAaaaBeforeJdkCollapsesIt() throws Exception {
        assertThrows(RagException.class, () -> RagDnsRecords.parseRecord(policy, "::ffff:8.8.8.8", true));
        assertThrows(RagException.class, () -> RagDnsRecords.parseRecord(policy, "10.0.0.1", false));
        assertThrows(RagException.class, () -> RagDnsRecords.parseRecord(policy, "8.8.8.8", true));
        assertEquals(16, RagDnsRecords.parseRecord(policy, "2606:4700::1111", true).getAddress().length);
    }

    @Test
    void hc5ConnectsToValidatedBytesWithFakeSocketAndNeverResolvesTwice() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        InetAddress address = ip(8, 8, 4, 4);
        RagPublicDnsResolver resolver = new RagPublicDnsResolver(policy, host -> {
            calls.incrementAndGet();
            return new InetAddress[]{address};
        });
        AtomicReference<SocketAddress> connected = new AtomicReference<>();
        Socket socket = new Socket() {
            @Override public void connect(SocketAddress endpoint, int timeout) { connected.set(endpoint); }
            @Override public void bind(SocketAddress endpoint) { }
            @Override public void setSoTimeout(int timeout) { }
            @Override public void setTcpNoDelay(boolean enabled) { }
            @Override public void setKeepAlive(boolean enabled) { }
        };
        var delegate = new DefaultHttpClientConnectionOperator(proxy -> {
            assertTrue(proxy == null || proxy == java.net.Proxy.NO_PROXY);
            return socket;
        }, null, resolver, RegistryBuilder.<org.apache.hc.client5.http.ssl.TlsSocketStrategy>create().build());
        var operator = new RagConnectionOperator(policy, delegate);
        ManagedHttpClientConnection connection = mock(ManagedHttpClientConnection.class);
        operator.connect(connection, new HttpHost("http", "public.example", 80), null,
                Timeout.ofSeconds(5), SocketConfig.custom().build(), new BasicHttpContext());
        assertEquals(address, ((InetSocketAddress) connected.get()).getAddress());
        assertFalse(((InetSocketAddress) connected.get()).isUnresolved());
        assertEquals(1, calls.get());
        verify(connection, times(2)).bind(socket); // HC5 binds before and after connect
        socket.close();
    }

    @Test
    void hc5RejectsPreResolvedAddressBeforeCallingSocketOperator() throws Exception {
        HttpClientConnectionOperator delegate = mock(HttpClientConnectionOperator.class);
        var operator = new RagConnectionOperator(policy, delegate);
        HttpHost addressed = new HttpHost("https", ip(8, 8, 8, 8), "public.example", 443);
        assertThrows(RagException.class, () -> operator.connect(mock(ManagedHttpClientConnection.class), addressed,
                null, Timeout.ofSeconds(5), SocketConfig.custom().build(), new BasicHttpContext()));
        verifyNoInteractions(delegate);
        assertThrows(RagException.class, () -> RagHttpTransportConfiguration.validateTarget(policy, addressed));
    }

    @Test
    void hc4ResolverBindsCheckedAddressAndRejectsProxyPreResolutionAndMixedAnswer() throws Exception {
        InetAddress publicIp = ip(8, 8, 8, 8);
        AtomicReference<InetAddress[]> answer = new AtomicReference<>(new InetAddress[]{publicIp});
        var addresses = RagElasticsearchTransportConfiguration.addressResolver(policy,
                new RagPublicDnsResolver(policy, host -> answer.get()));
        var target = new org.apache.http.HttpHost("public.example", 443, "https");
        var route = new org.apache.http.conn.routing.HttpRoute(target, null, true);
        var connected = (InetSocketAddress) addresses.resolveRemoteAddress(route);
        assertEquals(publicIp, connected.getAddress());
        assertFalse(connected.isUnresolved());
        answer.set(new InetAddress[]{publicIp, ip(127, 0, 0, 1)});
        assertThrows(RagException.class, () -> addresses.resolveRemoteAddress(route));
        assertThrows(RagException.class, () -> addresses.resolveRemoteAddress(new org.apache.http.conn.routing.HttpRoute(
                target, null, new org.apache.http.HttpHost("proxy.example", 8080), true)));
        assertThrows(RagException.class, () -> addresses.resolveRemoteAddress(new org.apache.http.conn.routing.HttpRoute(
                new org.apache.http.HttpHost(publicIp, "public.example", 443, "https"), null, true)));
    }

    @Test
    void boundedDnsUsesRemainingAbsoluteBudgetAndSanitizesOsErrors() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var resolver = new RagPublicDnsResolver(policy, host -> {
                try { Thread.sleep(1000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                return new InetAddress[0];
            }, executor);
            try (var deadline = RagHttpDeadline.open(Duration.ofMillis(30))) {
                RagException ex = assertThrows(RagException.class, () -> resolver.resolve("public.example"));
                assertEquals(RagErrorCode.TIMEOUT, ex.code());
            }
            var failure = new RagPublicDnsResolver(policy, host -> {
                throw new java.net.UnknownHostException("secret-url");
            });
            var ex = assertThrows(java.net.UnknownHostException.class, () -> failure.resolve("public.example"));
            assertFalse(ex.toString().contains("secret-url"));
            assertNull(ex.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void bootGetsPrimaryPrototypeBuilderAndEsCallbacksWithoutMakingRequests() {
        new ApplicationContextRunner().withUserConfiguration(RagHttpTransportConfiguration.class,
                        RagElasticsearchTransportConfiguration.class)
                .withConfiguration(AutoConfigurations.of(ElasticsearchRestClientAutoConfiguration.class))
                .withPropertyValues("spring.elasticsearch.uris=https://public.example:443")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    RestClient.Builder first = context.getBean(RestClient.Builder.class);
                    RestClient.Builder second = context.getBean("ragProtectedRestClientBuilder", RestClient.Builder.class);
                    assertNotSame(first, second);
                    assertThrows(RagException.class, () -> first.build().get().uri("http://127.0.0.1").retrieve().body(String.class));
                    assertTrue(context.containsBean("elasticsearchRestClient"));
                    var manager = context.getBean("ragHttpConnectionManager",
                            org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager.class);
                    assertFalse(manager.isClosed());
                });
    }

    @Test
    void realEsCustomizerDoesNotUseUnsupportedHc5Apis() {
        var builder = org.apache.http.client.config.RequestConfig.custom();
        new RagElasticsearchTransportConfiguration.ProtectedCustomizer(policy,
                mock(org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager.class)).customize(builder);
        var config = builder.build();
        assertFalse(config.isRedirectsEnabled());
        assertEquals(5000, config.getConnectTimeout());
        assertEquals(8000, config.getSocketTimeout());
    }
}
