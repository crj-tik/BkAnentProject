package com.bkanent.rag.security.http;

import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.io.ManagedHttpClientConnection;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;

/** Covers the HttpHost pre-resolved-address fast path on every physical connection. */
final class RagConnectionOperator implements HttpClientConnectionOperator {
    static final String DEADLINE = RagConnectionOperator.class.getName() + ".deadline";
    private final RagEndpointPolicy policy;
    private final HttpClientConnectionOperator delegate;

    RagConnectionOperator(RagEndpointPolicy policy, HttpClientConnectionOperator delegate) {
        this.policy = policy;
        this.delegate = delegate;
    }

    private void validate(HttpHost host, NamedEndpoint endpoint) {
        policy.validateHost(host.getHostName());
        if (host.getAddress() != null || !("http".equalsIgnoreCase(host.getSchemeName())
                || "https".equalsIgnoreCase(host.getSchemeName()))
                || (endpoint != null && (!host.getHostName().equalsIgnoreCase(endpoint.getHostName())
                || host.getPort() != endpoint.getPort()))) {
            throw RagEndpointPolicy.denied();
        }
    }

    private Timeout bounded(TimeValue configured, HttpContext context) {
        long nanos = Math.min(TimeValue.isPositive(configured) ? configured.toNanoseconds() : Long.MAX_VALUE,
                5_000_000_000L);
        Object deadline = context.getAttribute(DEADLINE);
        if (deadline instanceof Long absolute) {
            nanos = Math.min(nanos, RagHttpDeadline.remainingNanos(absolute));
        }
        return Timeout.ofNanoseconds(nanos);
    }

    @Override
    public void connect(ManagedHttpClientConnection connection, HttpHost host, InetSocketAddress local,
                        TimeValue timeout, SocketConfig config, HttpContext context) throws IOException {
        validate(host, null);
        delegate.connect(connection, host, local, bounded(timeout, context), config, context);
    }

    @Override
    public void connect(ManagedHttpClientConnection connection, HttpHost host, NamedEndpoint endpoint,
                        InetSocketAddress local, Timeout timeout, SocketConfig config, Object attachment,
                        HttpContext context) throws IOException {
        validate(host, endpoint);
        delegate.connect(connection, host, endpoint, local, bounded(timeout, context), config, attachment, context);
    }

    @Override
    public void upgrade(ManagedHttpClientConnection connection, HttpHost host, HttpContext context) throws IOException {
        validate(host, null);
        delegate.upgrade(connection, host, context);
    }

    @Override
    public void upgrade(ManagedHttpClientConnection connection, HttpHost host, NamedEndpoint endpoint,
                        Object attachment, HttpContext context) throws IOException {
        validate(host, endpoint);
        delegate.upgrade(connection, host, endpoint, attachment, context);
    }
}
