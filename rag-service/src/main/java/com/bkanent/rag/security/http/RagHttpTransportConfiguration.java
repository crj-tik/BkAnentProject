package com.bkanent.rag.security.http;

import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Real synchronous HC5 transport; deliberately does not configure WebClient or Milvus gRPC. */
@Configuration(proxyBeanMethods = false)
public class RagHttpTransportConfiguration {
    @Bean
    public RagEndpointPolicy ragEndpointPolicy(Environment environment) {
        // Bind all three settings together on every use, then freeze host/CIDR authorization.
        // Bracketed map keys preserve dots: internal-networks.[host.docker.internal][0].
        return RagEndpointPolicy.configured(() -> {
            try {
                return Binder.get(environment).bind("rag.http", Bindable.of(RagHttpPolicyConfiguration.class))
                        .orElseGet(() -> new RagHttpPolicyConfiguration("PUBLIC_ONLY", List.of(), Map.of()));
            } catch (RuntimeException ex) {
                throw RagEndpointPolicy.denied(); // do not retain untrusted values or Binder causes
            }
        });
    }

    @Bean(name = "ragDnsExecutor", destroyMethod = "shutdownNow")
    public ExecutorService ragDnsExecutor() {
        return new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), runnable -> {
            Thread thread = new Thread(runnable, "rag-http-dns");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public RagPublicDnsResolver ragPublicDnsResolver(RagEndpointPolicy policy,
                                                     @Qualifier("ragDnsExecutor") ExecutorService executor) {
        return new RagPublicDnsResolver(policy, new RagDnsRecords(policy), executor);
    }

    @Bean(name = "ragHttpDeadlineScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService ragHttpDeadlineScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, runnable -> {
            Thread thread = new Thread(runnable, "rag-http-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @Bean(name = "ragHttpConnectionManager", destroyMethod = "close")
    public PoolingHttpClientConnectionManager ragHttpConnectionManager(RagEndpointPolicy policy,
                                                                       RagPublicDnsResolver resolver) {
        return new PoolingHttpClientConnectionManagerBuilder() {
            @Override
            protected HttpClientConnectionOperator createConnectionOperator(SchemePortResolver ports,
                                                                              org.apache.hc.client5.http.DnsResolver dns,
                                                                              TlsSocketStrategy tls) {
                // Explicit NO_PROXY also blocks JVM SOCKS defaults on new Socket().
                var tlsRegistry = org.apache.hc.core5.http.config.RegistryBuilder.<TlsSocketStrategy>create()
                        .register("https", tls).build();
                return new RagConnectionOperator(policy,
                        new org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator(
                                proxy -> new java.net.Socket(java.net.Proxy.NO_PROXY), ports, dns, tlsRegistry));
            }
        }.setDnsResolver(resolver)
                .setMaxConnTotal(64).setMaxConnPerRoute(16)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(8)).build())
                .setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(Timeout.ofSeconds(8)).build())
                .build();
    }

    @Bean(name = "ragProtectedHttpClient", destroyMethod = "close")
    public CloseableHttpClient ragProtectedHttpClient(RagEndpointPolicy policy,
            @Qualifier("ragHttpConnectionManager") PoolingHttpClientConnectionManager manager) {
        return HttpClients.custom().setConnectionManager(manager).setConnectionManagerShared(true)
                .setRoutePlanner((target, context) -> {
                    validateTarget(policy, target);
                    // No SystemDefaultRoutePlanner, ProxySelector or request-config proxy.
                    int port = target.getPort() < 0 ? ("https".equalsIgnoreCase(target.getSchemeName()) ? 443 : 80)
                            : target.getPort();
                    return new HttpRoute(new HttpHost(target.getSchemeName(), target.getHostName(), port), null,
                            "https".equalsIgnoreCase(target.getSchemeName()));
                })
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableAuthCaching()
                .addRequestInterceptorFirst((request, entity, context) -> {
                    try {
                        policy.validateUrl(request.getUri());
                    } catch (java.net.URISyntaxException ex) {
                        throw RagEndpointPolicy.denied();
                    }
                    // The URI authority, route and TLS name must describe the same target.
                    var route = org.apache.hc.client5.http.protocol.HttpClientContext.adapt(context).getHttpRoute();
                    if (route == null || route.getProxyHost() != null || request.getAuthority() == null
                            || !request.getAuthority().getHostName().equalsIgnoreCase(route.getTargetHost().getHostName())) {
                        throw RagEndpointPolicy.denied();
                    }
                })
                .build();
    }

    static void validateTarget(RagEndpointPolicy policy, HttpHost target) {
        if (target == null || target.getAddress() != null) {
            throw RagEndpointPolicy.denied();
        }
        policy.validateUrl(target.toURI());
    }

    @Bean(name = "ragProtectedRestClientBuilder")
    @Qualifier("ragProtectedRestClientBuilder")
    @Primary
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RestClient.Builder ragProtectedRestClientBuilder(
            @Qualifier("ragProtectedHttpClient") CloseableHttpClient client, RagEndpointPolicy policy,
            @Qualifier("ragHttpDeadlineScheduler") ScheduledExecutorService scheduler) {
        // Primary feeds OpenAiEmbeddingAutoConfiguration's ObjectProvider<RestClient.Builder>.
        // Existing BGE code must inject this qualifier rather than create a fresh unmanaged builder.
        return RestClient.builder().requestFactory(new RagProtectedRequestFactory(client, policy, scheduler))
                .defaultStatusHandler(status -> status.is3xxRedirection(), (request, response) -> {
                    throw RagEndpointPolicy.denied();
                });
    }
}
