package com.bkanent.gateway.filter;

import com.bkanent.common.authz.*;
import com.bkanent.common.rpc.AuthPrincipalContext;
import com.bkanent.common.security.ServiceIdentityCrypto;
import com.bkanent.gateway.config.GatewayAuthorizationProperties;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/** Called at the existing -300 auth order, after route selection, before forwarding. */
@Component
@ConditionalOnProperty(prefix = "gateway.authorization", name = "enabled", havingValue = "true")
public class GatewayAuthorizationFilter {
    @DubboReference(group = "authorization", version = "1.0", protocol = "dubbo", retries = 0,
            timeout = 3000, check = false)
    private AuthAuthorizationRpcService authorizationRpcService;

    private final GatewayAuthorizationProperties properties;
    private final PrivateKey privateKey;
    private final Map<String, PublicKey> authorityKeys;
    private final SecureRandom random = new SecureRandom();

    public GatewayAuthorizationFilter(GatewayAuthorizationProperties properties) {
        this.properties = properties;
        properties.validate();
        this.privateKey = ServiceIdentityCrypto.loadPrivateKey(Path.of(properties.getPrivateKeyPath()));
        Map<String, PublicKey> keys = new HashMap<>();
        properties.getAuthorityPublicKeys().forEach((id, value) -> keys.put(id, ServiceIdentityCrypto.decodePublicKey(value)));
        this.authorityKeys = Map.copyOf(keys);
    }

    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = stripTrustedHeaders(exchange.getRequest());
        ServerWebExchange clean = exchange.mutate().request(request).build();
        return Mono.defer(() -> {
            String path = AuthzWireCodec.validatePath(request.getURI().getRawPath());
            List<RequestBinding.QueryParameter> query = AuthzWireCodec.parseQuery(request.getURI().getRawQuery());
            if (properties.getPublicRoutes().stream().anyMatch(rule -> rule.path().equals(path)
                    && rule.method().equals(request.getMethod().name()))) {
                return chain.filter(clean);
            }
            String token = token(request);
            if (token == null || token.isBlank()) return error(clean, 401, "AUTHENTICATION_REQUIRED");
            Route route = clean.getAttribute(GATEWAY_ROUTE_ATTR);
            // At -300 route filters have not run. No rewrite/body filter is assumed or guessed.
            if (route == null || !route.getFilters().isEmpty()
                    || route.getUri().getUserInfo() != null || route.getUri().getRawQuery() != null
                    || route.getUri().getRawFragment() != null
                    || (route.getUri().getRawPath() != null && !route.getUri().getRawPath().isEmpty()
                        && !"/".equals(route.getUri().getRawPath()))) {
                return error(clean, 503, "UNSUPPORTED_AUTHORIZATION_ROUTE");
            }
            String audience = audience(route);
            if (audience == null) return error(clean, 503, "AUTHORIZATION_ROUTE_UNMAPPED");
            MediaType contentType = request.getHeaders().getContentType();
            if (contentType != null && (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)
                    || "multipart".equalsIgnoreCase(contentType.getType())
                    || ("application".equalsIgnoreCase(contentType.getType())
                        && "x-ndjson".equalsIgnoreCase(contentType.getSubtype())))) {
                return error(clean, 415, "UNSUPPORTED_AUTHORIZATION_BODY");
            }
            if (request.getHeaders().getContentLength() > properties.getMaxBodyBytes()) {
                return error(clean, 413, "AUTHORIZATION_BODY_TOO_LARGE");
            }
            return readBody(request).flatMap(bytes -> Mono.fromCallable(() -> authorize(token, request.getMethod().name(), path, query, audience, bytes))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(result -> cachedRequest(request, clean, bytes, result))
                    .onErrorResume(AuthzException.class, failure -> Mono.error(new AdmissionFailure(
                            failure.code().httpStatus(), failure.code().name())))
                    .onErrorResume(failure -> failure instanceof AdmissionFailure ? Mono.error(failure)
                            : Mono.error(new AdmissionFailure(503, "AUTHORIZATION_UNAVAILABLE")))
                    .flatMap(forward -> chain.filter(clean.mutate().request(forward).build())))
                    .onErrorResume(DataBufferLimitException.class,
                            failure -> error(clean, 413, "AUTHORIZATION_BODY_TOO_LARGE"));
        }).onErrorResume(AdmissionFailure.class, failure -> error(clean, failure.status, failure.code))
                .onErrorResume(AuthzException.class, failure -> error(clean, failure.code().httpStatus(), failure.code().name()));
    }

    private Admission authorize(String token, String method, String path, List<RequestBinding.QueryParameter> query,
                                String audience, byte[] bytes) {
        if (authorizationRpcService == null) throw new AdmissionFailure(503, "AUTHORIZATION_UNAVAILABLE");
        long now = Instant.now().getEpochSecond();
        byte[] nonceBytes = new byte[32];
        random.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        String requestId = UUID.randomUUID().toString();
        // Policy is bound to this exact ingress, never read from body, actor/session headers or metadata.
        String bodyHash = AuthzCanonicalHash.bodyHash(bytes);
        RequestBinding binding = new RequestBinding(properties.getCallerService(), audience, method,
                path, path, query, requestId, null, null, bodyHash, bodyHash, nonce, now, now + 60);
        String requestHash = AuthzCanonicalHash.resolveRequestHash(token, binding);
        CallerProof proof = AuthzSignature.sign(new CallerProof(properties.getCallerService(), "auth-service",
                AuthzRpcOperation.RESOLVE_REQUEST_AUTHORIZATION, requestId, requestHash, nonce,
                now, now + 60, AuthzContract.ALGORITHM, properties.getKeyId(), null), privateKey);
        AuthzResponse response;
        try {
            response = authorizationRpcService.resolveRequestAuthorization(token, binding, proof);
        } catch (AuthzException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AdmissionFailure(503, "AUTHORIZATION_UNAVAILABLE");
        }
        try {
            AuthzWireCodec.verifyIngress(response, binding, token, audience, binding.method(), path, query,
                    bytes, authorityKeys, Instant.now());
            return new Admission(AuthzWireCodec.encodeResponse(response), AuthzWireCodec.encodeBinding(binding));
        } catch (AuthzException exception) {
            throw new AdmissionFailure(503, "AUTHORIZATION_RESPONSE_INVALID");
        }
    }

    private Mono<byte[]> readBody(ServerHttpRequest request) {
        return DataBufferUtils.join(request.getBody(), properties.getMaxBodyBytes()).map(buffer -> {
            try {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                return bytes;
            } finally {
                DataBufferUtils.release(buffer);
            }
        }).defaultIfEmpty(new byte[0]);
    }

    private ServerHttpRequest cachedRequest(ServerHttpRequest request, ServerWebExchange exchange,
                                           byte[] bytes, Admission admission) {
        ServerHttpRequest headers = request.mutate().headers(values -> {
            values.set(AuthzTransportHeaders.RESPONSE_HEADER, admission.response());
            values.set(AuthzTransportHeaders.REQUEST_BINDING_HEADER, admission.binding());
            values.remove(HttpHeaders.TRANSFER_ENCODING);
            values.setContentLength(bytes.length);
        }).build();
        return new ServerHttpRequestDecorator(headers) {
            @Override public Flux<org.springframework.core.io.buffer.DataBuffer> getBody() {
                return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(bytes)));
            }
        };
    }

    private String audience(Route route) {
        String byRoute = properties.getRouteAudiences().get(route.getId());
        String byService = "lb".equals(route.getUri().getScheme())
                ? properties.getServiceAudiences().get(route.getUri().getHost()) : null;
        if (byRoute != null && byService != null && !byRoute.equals(byService)) return null;
        return byRoute != null ? byRoute : byService;
    }

    private String token(ServerHttpRequest request) {
        List<String> values = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (values == null || values.isEmpty()) return null;
        if (values.size() != 1 || values.get(0).contains(",")) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        String value = values.get(0).trim();
        return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : value;
    }

    private ServerHttpRequest stripTrustedHeaders(ServerHttpRequest request) {
        return request.mutate().headers(headers -> {
            for (String name : List.copyOf(headers.keySet())) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.startsWith("x-bkanent-") || lower.startsWith("x-authenticated-")
                        || lower.startsWith("x-a2a-") || lower.startsWith("x-authz-")
                        || lower.startsWith("x-authorization-") || List.of("x-user-id", "x-actor-id",
                        "x-business-admin", "x-permission-revision", "x-credential-epoch", "x-run-id", "x-session-id")
                        .contains(lower) || name.equalsIgnoreCase(AuthPrincipalContext.USER_ID_HEADER)
                        || name.equalsIgnoreCase(AuthPrincipalContext.MARKER_HEADER)) headers.remove(name);
            }
        }).build();
    }

    private Mono<Void> error(ServerWebExchange exchange, int status, String code) {
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(status));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = ("{\"success\":false,\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }

    private record Admission(String response, String binding) { }
    private static final class AdmissionFailure extends RuntimeException {
        private final int status;
        private final String code;
        private AdmissionFailure(int status, String code) { super(code); this.status = status; this.code = code; }
    }
}
