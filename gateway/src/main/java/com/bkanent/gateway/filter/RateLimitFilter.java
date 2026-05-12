package com.bkanent.gateway.filter;

import com.bkanent.gateway.config.GatewayAccessProperties;
import com.bkanent.gateway.config.GatewayRateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RateLimitFilter 全局限流过滤器。
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final GatewayRateLimitProperties gatewayRateLimitProperties;
    private final GatewayAccessProperties gatewayAccessProperties;
    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public RateLimitFilter(GatewayRateLimitProperties gatewayRateLimitProperties,
                           GatewayAccessProperties gatewayAccessProperties) {
        this.gatewayRateLimitProperties = gatewayRateLimitProperties;
        this.gatewayAccessProperties = gatewayAccessProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!gatewayRateLimitProperties.isEnabled()) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        if (gatewayAccessProperties.isWhitelisted(path)) {
            return chain.filter(exchange);
        }

        long now = System.currentTimeMillis();
        long windowMillis = gatewayRateLimitProperties.getWindowSeconds() * 1000;
        String key = resolveClientIp(request) + ":" + resolveRouteKey(path);
        WindowCounter counter = counters.compute(key, (ignored, existing) -> refreshWindow(existing, now, windowMillis));
        int current = counter.count().incrementAndGet();
        if (current > gatewayRateLimitProperties.getRequestsPerWindow()) {
            log.warn("触发网关限流 path={} clientIp={} count={}", path, resolveClientIp(request), current);
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"success\":false,\"code\":\"429\",\"message\":\"请求过于频繁，请稍后重试\"}";
            return exchange.getResponse().writeWith(Mono.just(
                    exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))
            ));
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -200;
    }

    private WindowCounter refreshWindow(WindowCounter existing, long now, long windowMillis) {
        if (existing == null || now - existing.windowStart() >= windowMillis) {
            return new WindowCounter(now, new AtomicInteger(0));
        }
        return existing;
    }

    private String resolveClientIp(ServerHttpRequest request) {
        String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        return remoteAddress == null ? "unknown" : remoteAddress.getAddress().getHostAddress();
    }

    private String resolveRouteKey(String path) {
        String[] segments = path.split("/");
        return segments.length > 1 ? segments[1] : "root";
    }

    /**
     * WindowCounter 固定窗口计数器。
     */
    private record WindowCounter(long windowStart, AtomicInteger count) {
    }
}
