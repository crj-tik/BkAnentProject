package com.bkanent.gateway.filter;

import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.common.rpc.AuthPrincipalContext;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.bkanent.gateway.config.GatewayAccessProperties;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * AuthTokenFilter 认证鉴权过滤器。
 */
@Component
public class AuthTokenFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenFilter.class);

    @DubboReference(check = false)
    private AuthPermissionRpcService authPermissionRpcService;

    private final GatewayAccessProperties gatewayAccessProperties;

    public AuthTokenFilter(GatewayAccessProperties gatewayAccessProperties) {
        this.gatewayAccessProperties = gatewayAccessProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = stripClientPrincipalHeaders(exchange.getRequest());
        ServerWebExchange sanitizedExchange = exchange.mutate().request(request).build();
        String path = request.getURI().getPath();
        if (gatewayAccessProperties.isWhitelisted(path)) {
            return chain.filter(sanitizedExchange);
        }

        if (authPermissionRpcService == null) {
            log.warn("认证服务未就绪，拒绝访问 path={}", path);
            return writeJsonResponse(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE, "认证服务暂不可用");
        }

        String token = normalizeToken(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (token == null || token.isBlank()) {
            log.warn("请求缺少认证令牌 path={}", path);
            return writeJsonResponse(sanitizedExchange, HttpStatus.UNAUTHORIZED, "缺少认证令牌");
        }

        AuthenticatedPrincipal principal;
        try {
            principal = authPermissionRpcService.resolvePrincipal(token);
        } catch (RuntimeException exception) {
            log.warn("认证服务调用失败 path={} reason={}", path, exception.getClass().getSimpleName());
            return writeJsonResponse(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE, "认证服务暂不可用");
        }
        if (principal == null || principal.userId() == null) {
            log.warn("认证令牌校验失败 path={}", path);
            return writeJsonResponse(sanitizedExchange, HttpStatus.UNAUTHORIZED, "认证令牌无效");
        }
        ServerHttpRequest authenticatedRequest = request.mutate()
                .header(AuthPrincipalContext.USER_ID_HEADER, String.valueOf(principal.userId()))
                .header(AuthPrincipalContext.MARKER_HEADER, AuthPrincipalContext.MARKER_VALUE)
                .build();
        return chain.filter(sanitizedExchange.mutate().request(authenticatedRequest).build());
    }

    @Override
    public int getOrder() {
        return -300;
    }

    private ServerHttpRequest stripClientPrincipalHeaders(ServerHttpRequest request) {
        return request.mutate().headers(headers -> {
            headers.remove("X-User-Id");
            headers.remove(AuthPrincipalContext.USER_ID_HEADER);
            headers.remove(AuthPrincipalContext.MARKER_HEADER);
        }).build();
    }

    private String normalizeToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return authorization;
        }
        if (authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return authorization.trim();
    }

    private Mono<Void> writeJsonResponse(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"success\":false,\"code\":\"" + status.value() + "\",\"message\":\"" + message + "\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
