package com.bkanent.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * RequestLoggingFilter 请求响应日志过滤器。
 */
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        long startTime = System.currentTimeMillis();

        exchange.getResponse().getHeaders().add(TRACE_ID_HEADER, traceId);
        log.info("网关收到请求 traceId={} method={} path={} clientIp={}",
                traceId, request.getMethod(), request.getURI().getPath(), resolveClientIp(request));

        return chain.filter(exchange)
                .doOnSuccess(unused -> log.info("网关请求完成 traceId={} status={} costMs={}",
                        traceId,
                        exchange.getResponse().getStatusCode(),
                        System.currentTimeMillis() - startTime))
                .doOnError(ex -> log.error("网关请求异常 traceId={} costMs={} message={}",
                        traceId,
                        System.currentTimeMillis() - startTime,
                        ex.getMessage(),
                        ex));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private String resolveClientIp(ServerHttpRequest request) {
        String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        return remoteAddress == null ? "unknown" : remoteAddress.getAddress().getHostAddress();
    }
}
