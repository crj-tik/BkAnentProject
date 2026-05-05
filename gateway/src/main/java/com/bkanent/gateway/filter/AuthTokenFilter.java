package com.bkanent.gateway.filter;

import com.bkanent.common.rpc.AuthPermissionRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;

@Component
public class AuthTokenFilter implements GlobalFilter, Ordered {

    private static final Set<String> WHITELIST_PREFIXES = Set.of(
            "/auth/login",
            "/auth/logout",
            "/actuator",
            "/gateway/health"
    );

    @DubboReference(check = false)
    private AuthPermissionRpcService authPermissionRpcService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        if (WHITELIST_PREFIXES.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        String token = request.getHeaders().getFirst("Authorization");
        boolean valid = authPermissionRpcService != null && authPermissionRpcService.validateToken(token);
        if (!valid) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
