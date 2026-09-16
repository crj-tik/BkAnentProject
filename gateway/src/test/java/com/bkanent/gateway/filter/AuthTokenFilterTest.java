package com.bkanent.gateway.filter;

import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.common.rpc.AuthPrincipalContext;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.bkanent.gateway.config.GatewayAccessProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthTokenFilterTest {

    private AuthPermissionRpcService authPermissionRpcService;
    private AuthTokenFilter filter;
    private ServerExchangeCapture capture;

    @BeforeEach
    void setUp() {
        authPermissionRpcService = mock(AuthPermissionRpcService.class);
        filter = new AuthTokenFilter(new GatewayAccessProperties());
        ReflectionTestUtils.setField(filter, "authPermissionRpcService", authPermissionRpcService);
        capture = new ServerExchangeCapture();
    }

    @Test
    void stripsForgedHeadersAndInjectsResolvedPrincipal() {
        when(authPermissionRpcService.resolvePrincipal("token"))
                .thenReturn(new AuthenticatedPrincipal(7L));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/agent/run")
                        .header("Authorization", "Bearer token")
                        .header("X-User-Id", "999")
                        .header(AuthPrincipalContext.USER_ID_HEADER, "999")
                        .header(AuthPrincipalContext.MARKER_HEADER, "client")
                        .build());

        filter.filter(exchange, capture).block();

        assertEquals("7", capture.exchange.getRequest().getHeaders()
                .getFirst(AuthPrincipalContext.USER_ID_HEADER));
        assertEquals(AuthPrincipalContext.MARKER_VALUE, capture.exchange.getRequest().getHeaders()
                .getFirst(AuthPrincipalContext.MARKER_HEADER));
        assertNull(capture.exchange.getRequest().getHeaders().getFirst("X-User-Id"));
        verify(authPermissionRpcService).resolvePrincipal("token");
    }

    @Test
    void rejectsInvalidPrincipalWithoutForwarding() {
        when(authPermissionRpcService.resolvePrincipal("token")).thenReturn(null);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/agent/run")
                        .header("Authorization", "Bearer token")
                        .build());

        filter.filter(exchange, capture).block();

        assertEquals(401, exchange.getResponse().getStatusCode().value());
        assertFalse(capture.called);
    }

    private static final class ServerExchangeCapture implements GatewayFilterChain {
        private boolean called;
        private ServerWebExchange exchange;

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            this.called = true;
            this.exchange = exchange;
            return Mono.empty();
        }
    }
}
