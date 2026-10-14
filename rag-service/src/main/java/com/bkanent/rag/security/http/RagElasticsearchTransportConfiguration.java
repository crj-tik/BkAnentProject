package com.bkanent.rag.security.http;

import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.conn.ManagedNHttpClientConnectionFactory;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.conn.NoopIOSessionStrategy;
import org.apache.http.nio.conn.SchemeIOSessionStrategy;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.nio.pool.SocketAddressResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.elasticsearch.RestClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/** Elasticsearch 8.18.1 uses HC4 async, not the HC5 Spring RestClient connection manager. */
@Configuration(proxyBeanMethods = false)
public class RagElasticsearchTransportConfiguration {
    // Boot's singleton ES RestClient owns startup/close; Spring's idempotent shutdown also cleans up
    // an INACTIVE manager when no client is constructed (for example, failed context initialization).
    @Bean(name = "ragEsConnectionManager", destroyMethod = "shutdown")
    public PoolingNHttpClientConnectionManager ragEsConnectionManager(RagEndpointPolicy policy,
                                                                      RagPublicDnsResolver resolver) throws IOException {
        var reactor = new DefaultConnectingIOReactor(IOReactorConfig.custom()
                .setIoThreadCount(2).setConnectTimeout(5000).setSoTimeout(8000).build());
        var registry = RegistryBuilder.<SchemeIOSessionStrategy>create()
                .register("http", NoopIOSessionStrategy.INSTANCE)
                .register("https", SSLIOSessionStrategy.getDefaultStrategy()).build();
        var manager = new ReactorConnectionManager(reactor, registry, addressResolver(policy, resolver));
        manager.setMaxTotal(64);
        manager.setDefaultMaxPerRoute(16);
        return manager;
    }

    /** Package-private lifecycle observation; no endpoint, exception details or reflective access. */
    static final class ReactorConnectionManager extends PoolingNHttpClientConnectionManager {
        private final DefaultConnectingIOReactor reactor;

        ReactorConnectionManager(DefaultConnectingIOReactor reactor,
                org.apache.http.config.Registry<SchemeIOSessionStrategy> registry,
                SocketAddressResolver<HttpRoute> resolver) {
            super(reactor, ManagedNHttpClientConnectionFactory.INSTANCE, registry, resolver);
            this.reactor = reactor;
        }

        org.apache.http.nio.reactor.IOReactorStatus reactorStatus() { return reactor.getStatus(); }
    }

    static SocketAddressResolver<HttpRoute> addressResolver(RagEndpointPolicy policy, RagPublicDnsResolver dns) {
        return new SocketAddressResolver<>() {
            public SocketAddress resolveLocalAddress(HttpRoute route) {
                return route.getLocalAddress() == null ? null : new InetSocketAddress(route.getLocalAddress(), 0);
            }

            public SocketAddress resolveRemoteAddress(HttpRoute route) throws IOException {
                validateRoute(policy, route);
                HttpHost host = route.getTargetHost();
                InetAddress[] addresses = dns.resolve(host.getHostName());
                int port = host.getPort() < 0 ? ("https".equalsIgnoreCase(host.getSchemeName()) ? 443 : 80)
                        : host.getPort();
                // HC4 stock resolver also chooses the first answer, but our resolver validates ALL first.
                // InetSocketAddress(InetAddress,port) binds those bytes without another DNS lookup.
                return new InetSocketAddress(addresses[0], port);
            }
        };
    }

    static void validateRoute(RagEndpointPolicy policy, HttpRoute route) {
        HttpHost host = route.getTargetHost();
        if (route.getProxyHost() != null || route.isTunnelled() || host.getAddress() != null) {
            throw RagEndpointPolicy.denied();
        }
        policy.validateUrl(host.toURI());
    }

    @Bean
    public RestClientBuilderCustomizer ragElasticsearchRestClientBuilderCustomizer(RagEndpointPolicy policy,
            @Qualifier("ragEsConnectionManager") PoolingNHttpClientConnectionManager manager) {
        return new ProtectedCustomizer(policy, manager);
    }

    static final class ProtectedCustomizer implements RestClientBuilderCustomizer, Ordered {
        private final RagEndpointPolicy policy;
        private final PoolingNHttpClientConnectionManager manager;

        ProtectedCustomizer(RagEndpointPolicy policy, PoolingNHttpClientConnectionManager manager) {
            this.policy = policy;
            this.manager = manager;
        }

        public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

        public void customize(org.elasticsearch.client.RestClientBuilder builder) {
            // Boot invokes the HttpAsyncClientBuilder/RequestConfig overloads below itself.
        }

        public void customize(HttpAsyncClientBuilder builder) {
            // HC4 shared=true suppresses the client's reactor thread, not just manager destruction.
            builder.setConnectionManager(manager).setConnectionManagerShared(false)
                    .setRoutePlanner((target, request, context) -> {
                        if (target == null || target.getAddress() != null) {
                            throw RagEndpointPolicy.denied();
                        }
                        policy.validateUrl(target.toURI());
                        int port = target.getPort() < 0 ? ("https".equalsIgnoreCase(target.getSchemeName()) ? 443 : 80)
                                : target.getPort();
                        return new HttpRoute(new HttpHost(target.getHostName(), port, target.getSchemeName()), null,
                                "https".equalsIgnoreCase(target.getSchemeName()));
                    })
                    .setRedirectStrategy(new org.apache.http.client.RedirectStrategy() {
                        public boolean isRedirected(org.apache.http.HttpRequest request,
                                org.apache.http.HttpResponse response, org.apache.http.protocol.HttpContext context) {
                            return false;
                        }
                        public org.apache.http.client.methods.HttpUriRequest getRedirect(org.apache.http.HttpRequest request,
                                org.apache.http.HttpResponse response, org.apache.http.protocol.HttpContext context) {
                            throw RagEndpointPolicy.denied();
                        }
                    })
                    .disableCookieManagement().disableAuthCaching()
                    .addInterceptorFirst((org.apache.http.HttpRequestInterceptor) (request, context) -> {
                        var route = org.apache.http.client.protocol.HttpClientContext.adapt(context).getHttpRoute();
                        if (!(route instanceof HttpRoute actual)) {
                            throw RagEndpointPolicy.denied();
                        }
                        validateRoute(policy, actual);
                        String target = request.getRequestLine().getUri();
                        if (!target.startsWith("/") || target.startsWith("//")) {
                            policy.validateUrl(target);
                        }
                    });
        }

        public void customize(RequestConfig.Builder builder) {
            builder.setRedirectsEnabled(false).setConnectTimeout(5000).setConnectionRequestTimeout(5000)
                    .setSocketTimeout(8000);
        }
    }
}
