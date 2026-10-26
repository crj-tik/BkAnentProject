package com.bkanent.agent.security;

import com.bkanent.agent.authorization.AgentAuthorizationConsumer;
import com.bkanent.agent.authorization.AgentAuthorizationScope;
import com.bkanent.agent.config.AgentAuthorizationProperties;
import com.bkanent.common.authz.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Enumeration;
import java.security.SecureRandom;
import java.security.PrivateKey;

/** Only the real transport header supplies a token; caller metadata is never inspected. */
@Component
@EnableConfigurationProperties(AgentAuthorizationProperties.class)
public class AgentRagCredentialFilter extends OncePerRequestFilter {
    private final AgentRagCredentialLeaseStore credentials;
    private final AgentAuthorizationProperties authorization;
    private final ObjectProvider<AgentAuthorizationConsumer> consumer;
    private final ObjectProvider<AuthAuthorizationRpcService> authorizationRpc;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private volatile PrivateKey directAuthSigningKey;

    public AgentRagCredentialFilter(AgentRagCredentialLeaseStore credentials) {
        this(credentials, new AgentAuthorizationProperties(), null, null);
    }
    @Autowired
    public AgentRagCredentialFilter(AgentRagCredentialLeaseStore credentials, AgentAuthorizationProperties authorization,
                                    ObjectProvider<AgentAuthorizationConsumer> consumer,
                                    ObjectProvider<AuthAuthorizationRpcService> authorizationRpc) {
        this(credentials, authorization, consumer, authorizationRpc, Clock.systemUTC());
    }
    public AgentRagCredentialFilter(AgentRagCredentialLeaseStore credentials, AgentAuthorizationProperties authorization,
                                    ObjectProvider<AgentAuthorizationConsumer> consumer,
                                    ObjectProvider<AuthAuthorizationRpcService> authorizationRpc, Clock clock) {
        this.credentials = credentials;
        this.authorization = authorization;
        this.consumer = consumer;
        this.authorizationRpc = authorizationRpc;
        this.clock = clock;
    }
    public AgentRagCredentialFilter(AgentRagCredentialLeaseStore credentials, AgentAuthorizationProperties authorization,
                                    ObjectProvider<AgentAuthorizationConsumer> consumer) {
        this(credentials, authorization, consumer, null);
    }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = authorization.isEnabled() ? request.getRequestURI() : request.getServletPath();
        return !(path.equals("/agent") || path.startsWith("/agent/"));
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (authorization.isEnabled()) {
            doVersioned(request, response, chain);
            return;
        }
        try (var ignored = credentials.openAuthorization(request.getHeader("Authorization"))) {
            if (!request.getServletPath().equals("/agent/health")) credentials.currentUserId();
            chain.doFilter(request, response);
        } catch (AgentRagAuthenticationException | com.bkanent.common.rag.RagException exception) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":false,\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"AUTHENTICATION_REQUIRED\",\"data\":null}");
        } catch (ServletException | IOException exception) { throw exception; }
        catch (Exception exception) { throw new ServletException("credential context cleanup failed", exception); }
    }

    private void doVersioned(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("GET".equals(request.getMethod()) && "/agent/health".equals(request.getRequestURI())
                && request.getHeader(AuthzTransportHeaders.RESPONSE_HEADER) == null
                && request.getHeader(AuthzTransportHeaders.REQUEST_BINDING_HEADER) == null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            String tokenHeader = singleHeader(request, "Authorization");
            if (!tokenHeader.regionMatches(true, 0, "Bearer ", 0, 7)) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
            String token = tokenHeader.substring(7).trim();
            if (token.isEmpty() || token.length() > 16384 || token.indexOf(',') >= 0
                    || token.chars().anyMatch(Character::isWhitespace)) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
            String responseHeader = optionalSingleHeader(request, AuthzTransportHeaders.RESPONSE_HEADER);
            String bindingHeader = optionalSingleHeader(request, AuthzTransportHeaders.REQUEST_BINDING_HEADER);
            if ((responseHeader == null) != (bindingHeader == null))
                throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
            byte[] body = request.getInputStream().readNBytes(authorization.getMaxBodyBytes() + 1);
            if (body.length > authorization.getMaxBodyBytes()) throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
            AgentAuthorizationConsumer verifier = consumer == null ? null : consumer.getIfAvailable();
            if (verifier == null) throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
            AuthzResponse authResponse;
            RequestBinding binding;
            if (responseHeader == null) {
                // A direct service request gets its own signed Auth admission. Never trust marker/user headers.
                var direct = resolveDirect(request, token, body);
                authResponse = direct.response();
                binding = direct.binding();
            } else {
                authResponse = AuthzWireCodec.decodeResponse(responseHeader);
                binding = AuthzWireCodec.decodeBinding(bindingHeader);
            }
            var entry = verifier.verify(authResponse, binding, token, request.getMethod(), request.getRequestURI(),
                    request.getQueryString(), body);
            request.setAttribute(AgentAuthorizationScope.VERIFIED_ATTRIBUTE, entry);
            request.setAttribute(AgentAuthorizationScope.RESPONSE_ATTRIBUTE, authResponse);
            request.setAttribute(AgentAuthorizationScope.BINDING_ATTRIBUTE, binding);
            try (var scope = AgentAuthorizationScope.open(entry);
                 var ignored = credentials.openVerifiedAuthorization(token, entry)) {
                chain.doFilter(new ReplayRequest(request, body), response);
            } finally {
                verifier.releaseIngress(entry);
                request.removeAttribute(AgentAuthorizationScope.VERIFIED_ATTRIBUTE);
                request.removeAttribute(AgentAuthorizationScope.RESPONSE_ATTRIBUTE);
                request.removeAttribute(AgentAuthorizationScope.BINDING_ATTRIBUTE);
            }
        } catch (AuthzException failure) {
            writeFailure(response, failure.code());
        } catch (AgentRagAuthenticationException failure) {
            writeFailure(response, AuthzErrorCode.AUTHENTICATION_REQUIRED);
        } catch (com.bkanent.common.rag.RagException failure) {
            writeFailure(response, switch (failure.code()) {
                case AUTHENTICATION_FAILED -> AuthzErrorCode.AUTHENTICATION_FAILED;
                case AUTHENTICATION_EXPIRED -> AuthzErrorCode.EXPIRED;
                case FORBIDDEN -> AuthzErrorCode.PERMISSION_DENIED;
                case INVALID_ARGUMENT -> AuthzErrorCode.INVALID_ARGUMENT;
                default -> AuthzErrorCode.AUTHORIZATION_UNAVAILABLE;
            });
        } catch (ServletException failure) {
            Throwable cause = failure;
            for (int depth = 0; depth < 16 && cause != null; depth++, cause = cause.getCause()) {
                if (cause instanceof AuthzException denied) {
                    writeFailure(response, denied.code());
                    return;
                }
            }
            throw failure;
        } catch (IOException failure) { throw failure; }
        catch (Exception failure) { throw new ServletException("authorization context cleanup failed"); }
    }

    private DirectAdmission resolveDirect(HttpServletRequest request, String token, byte[] body) {
        AuthAuthorizationRpcService provider = authorizationRpc == null ? null : authorizationRpc.getIfAvailable();
        if (provider == null) throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
        long now = clock.instant().getEpochSecond();
        byte[] nonceBytes = new byte[32];
        random.nextBytes(nonceBytes);
        String nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        String requestId = java.util.UUID.randomUUID().toString();
        String path = AuthzWireCodec.validatePath(request.getRequestURI());
        var query = AuthzWireCodec.parseQuery(request.getQueryString());
        String bodyHash = AuthzCanonicalHash.bodyHash(body);
        var binding = new RequestBinding(authorization.getCallerService(), authorization.getAudience(),
                request.getMethod(), path, path, query, requestId, null, null, bodyHash, bodyHash,
                nonce, now, now + AuthzDeadlines.REQUEST_SECONDS);
        String requestHash = AuthzCanonicalHash.resolveRequestHash(token, binding);
        var proof = AuthzSignature.sign(new CallerProof(authorization.getCallerService(), "auth-service",
                AuthzRpcOperation.RESOLVE_REQUEST_AUTHORIZATION, requestId, requestHash, nonce,
                now, now + AuthzDeadlines.REQUEST_SECONDS, AuthzContract.ALGORITHM,
                authorization.getKeyId(), null), directAuthSigningKey());
        AuthzResponse response;
        try {
            response = provider.resolveRequestAuthorization(token, binding, proof);
        } catch (AuthzException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
        AuthzWireCodec.verifyIngress(response, binding, token, authorization.getAudience(),
                request.getMethod(), path, query, body, authorization.authorityKeys(), clock.instant());
        return new DirectAdmission(response, binding);
    }

    private record DirectAdmission(AuthzResponse response, RequestBinding binding) { }

    private PrivateKey directAuthSigningKey() {
        PrivateKey key = directAuthSigningKey;
        if (key != null) return key;
        synchronized (this) {
            if (directAuthSigningKey == null) directAuthSigningKey = authorization.signingKey();
            return directAuthSigningKey;
        }
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.isEmpty() || value.indexOf(',') >= 0) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        }
        return value;
    }
    private static String optionalSingleHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.isEmpty() || value.indexOf(',') >= 0)
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        return value;
    }
    private static void writeFailure(HttpServletResponse response, AuthzErrorCode code) throws IOException {
        response.setStatus(code.httpStatus());
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"code\":\"" + code.name()
                + "\",\"message\":\"" + code.name() + "\",\"data\":null}");
    }

    private static final class ReplayRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        ReplayRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                private final ByteArrayInputStream input = new ByteArrayInputStream(body);
                private ReadListener listener;
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] bytes, int offset, int length) { return input.read(bytes, offset, length); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener value) {
                    if (value == null || listener != null) throw new IllegalStateException("Read listener already set or missing");
                    listener = value;
                    try { if (!isFinished()) value.onDataAvailable(); if (isFinished()) value.onAllDataRead(); }
                    catch (IOException failure) { value.onError(failure); }
                }
            };
        }
        @Override public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(), encoding == null
                    ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding)));
        }
    }
}
