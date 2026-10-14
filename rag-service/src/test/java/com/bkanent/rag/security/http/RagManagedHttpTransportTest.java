package com.bkanent.rag.security.http;

import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.io.ManagedHttpClientConnection;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.protocol.BasicHttpContext;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.bkanent.rag.security.http.RagManagedEndpointPolicyTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagManagedHttpTransportTest {
    @Test
    void hc5ActualOperatorConnectsSelectedPrivateBytesPreservesTlsNameAndRechecksReconnection() throws Exception {
        var live = new AtomicReference<>(config(List.of("elasticsearch"), Map.of("elasticsearch", List.of("172.24.0.0/16"))));
        var policy = RagEndpointPolicy.configured(live::get);
        InetAddress first = address("172.24.0.2");
        InetAddress second = address("172.24.0.3");
        var answer = new AtomicReference<>(new InetAddress[]{first, second});
        AtomicInteger dnsCalls = new AtomicInteger();
        var dns = new RagPublicDnsResolver(policy, host -> { dnsCalls.incrementAndGet(); return answer.get(); });
        var connections = new AtomicInteger();
        var connected = new AtomicReference<SocketAddress>();
        Socket socket = new Socket() {
            @Override public void connect(SocketAddress endpoint, int timeout) {
                connections.incrementAndGet();
                connected.set(endpoint);
                assertTrue(timeout > 0 && timeout <= 5000);
            }
            @Override public void bind(SocketAddress endpoint) { }
            @Override public void setSoTimeout(int timeout) { }
            @Override public void setTcpNoDelay(boolean value) { }
            @Override public void setKeepAlive(boolean value) { }
        };
        TlsSocketStrategy tls = mock(TlsSocketStrategy.class);
        when(tls.upgrade(eq(socket), eq("elasticsearch"), eq(9200), any(), any()))
                .thenReturn(mock(javax.net.ssl.SSLSocket.class));
        var delegate = new DefaultHttpClientConnectionOperator(proxy -> socket, null, dns,
                RegistryBuilder.<TlsSocketStrategy>create().register("https", tls).build());
        var operator = new RagConnectionOperator(policy, delegate);
        var connection = mock(ManagedHttpClientConnection.class);
        // HTTP physical connect, followed by a real operator TLS upgrade with the original hostname.
        HttpHost host = new HttpHost("http", "elasticsearch", 9200);
        operator.connect(connection, host, null, Timeout.ofSeconds(5), SocketConfig.custom().build(), new BasicHttpContext());
        assertEquals(first, ((InetSocketAddress) connected.get()).getAddress());
        assertFalse(((InetSocketAddress) connected.get()).isUnresolved());
        assertEquals(1, dnsCalls.get());
        verify(connection, times(2)).bind(socket);
        when(connection.getSocket()).thenReturn(socket);
        operator.upgrade(connection, new HttpHost("https", "elasticsearch", 9200), new BasicHttpContext());
        verify(tls).upgrade(eq(socket), eq("elasticsearch"), eq(9200), any(), any());
        answer.set(new InetAddress[]{first, address("192.168.1.1")});
        denied(() -> operator.connect(connection, host, null, Timeout.ofSeconds(5),
                SocketConfig.custom().build(), new BasicHttpContext()));
        assertEquals(1, connections.get());
        answer.set(new InetAddress[]{second});
        operator.connect(connection, host, null, Timeout.ofSeconds(5), SocketConfig.custom().build(), new BasicHttpContext());
        assertEquals(second, ((InetSocketAddress) connected.get()).getAddress());
        live.set(config(List.of("elasticsearch"), Map.of()));
        denied(() -> operator.connect(connection, host, null, Timeout.ofSeconds(5),
                SocketConfig.custom().build(), new BasicHttpContext()));
        assertEquals(2, connections.get());
        socket.close();
    }

    @Test
    void hc4RealAsyncPoolConnectsFakeSocketToChosenBytesAndRechecksNextLease() throws Exception {
        var policy = managed(List.of("elasticsearch"), Map.of("elasticsearch", List.of("172.24.0.0/16")));
        var answer = new AtomicReference<>(new InetAddress[]{address("172.24.0.2"), address("172.24.0.3")});
        var dnsCalls = new AtomicInteger();
        var dns = new RagPublicDnsResolver(policy, host -> { dnsCalls.incrementAndGet(); return answer.get(); });
        var reactor = mock(org.apache.http.nio.reactor.ConnectingIOReactor.class);
        when(reactor.getStatus()).thenReturn(org.apache.http.nio.reactor.IOReactorStatus.ACTIVE);
        var connected = new AtomicReference<SocketAddress>();
        var connectCalls = new AtomicInteger();
        Socket fakeSocket = new Socket() {
            @Override public void connect(SocketAddress endpoint, int timeout) {
                connected.set(endpoint);
                connectCalls.incrementAndGet();
            }
        };
        var callback = new AtomicReference<org.apache.http.nio.reactor.SessionRequestCallback>();
        var sessionRequest = new AtomicReference<org.apache.http.nio.reactor.SessionRequest>();
        when(reactor.connect(any(SocketAddress.class), nullable(SocketAddress.class), any(), any())).thenAnswer(call -> {
            SocketAddress remote = call.getArgument(0);
            fakeSocket.connect(remote, 5000); // real pool invoked reactor connect with bound bytes, never a hostname
            var request = mock(org.apache.http.nio.reactor.SessionRequest.class);
            var session = mock(org.apache.http.nio.reactor.IOSession.class);
            when(session.getRemoteAddress()).thenReturn(remote);
            when(request.getRemoteAddress()).thenReturn(remote);
            when(request.getAttachment()).thenReturn(call.getArgument(2));
            when(request.getSession()).thenReturn(session);
            callback.set(call.getArgument(3));
            sessionRequest.set(request);
            return request;
        });
        org.apache.http.nio.conn.NHttpConnectionFactory<org.apache.http.nio.conn.ManagedNHttpClientConnection> factory =
                (session, config) -> mock(org.apache.http.nio.conn.ManagedNHttpClientConnection.class);
        var registry = org.apache.http.config.RegistryBuilder.<org.apache.http.nio.conn.SchemeIOSessionStrategy>create()
                .register("http", org.apache.http.nio.conn.NoopIOSessionStrategy.INSTANCE).build();
        var manager = new org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager(reactor, factory, registry,
                RagElasticsearchTransportConfiguration.addressResolver(policy, dns));
        var target = new org.apache.http.HttpHost("elasticsearch", 9200, "http");
        var route = new org.apache.http.conn.routing.HttpRoute(target);
        try {
            var pending = manager.requestConnection(route, null, 5000, 5000, TimeUnit.MILLISECONDS, null);
            assertEquals(address("172.24.0.2"), ((InetSocketAddress) connected.get()).getAddress());
            assertFalse(((InetSocketAddress) connected.get()).isUnresolved());
            callback.get().completed(sessionRequest.get());
            var leased = pending.get(1, TimeUnit.SECONDS);
            manager.releaseConnection(leased, null, 0, TimeUnit.MILLISECONDS); // mocked connection is closed, so next lease reconnects
            assertEquals(1, dnsCalls.get());
            answer.set(new InetAddress[]{address("172.24.0.3")});
            var next = manager.requestConnection(route, null, 5000, 5000, TimeUnit.MILLISECONDS, null);
            assertEquals(address("172.24.0.3"), ((InetSocketAddress) connected.get()).getAddress());
            callback.get().completed(sessionRequest.get());
            manager.releaseConnection(next.get(1, TimeUnit.SECONDS), null, 0, TimeUnit.MILLISECONDS);
            assertEquals(2, dnsCalls.get());
            answer.set(new InetAddress[]{address("172.24.0.3"), address("127.0.0.1")});
            denied(() -> manager.requestConnection(route, null, 5000, 5000, TimeUnit.MILLISECONDS, null));
            assertEquals(2, connectCalls.get());
            denied(() -> manager.requestConnection(new org.apache.http.conn.routing.HttpRoute(
                    new org.apache.http.HttpHost(address("172.24.0.3"), "elasticsearch", 9200, "http")),
                    null, 5000, 5000, TimeUnit.MILLISECONDS, null));
            denied(() -> manager.requestConnection(new org.apache.http.conn.routing.HttpRoute(target, null,
                    new org.apache.http.HttpHost("proxy.example", 8080), false), null, 5000, 5000, TimeUnit.MILLISECONDS, null));
            assertEquals(2, connectCalls.get());
        } finally {
            manager.shutdown();
            fakeSocket.close();
        }
    }
}
