package com.bkanent.common.authz;

import com.bkanent.common.security.ServiceIdentityCrypto;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.PublicKey;
import java.time.Clock;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/** Local downstream verification for one Gateway-signed request; no Auth call is made here. */
public class AuthzIngressFilter extends OncePerRequestFilter {
    private final String pathPrefix;
    private final String audience;
    private final int maxBodyBytes;
    private final Map<String, PublicKey> authorityKeys;
    private final Clock clock;

    public AuthzIngressFilter(String pathPrefix, String audience, int maxBodyBytes,
                              Map<String, String> encodedAuthorityKeys) {
        this(pathPrefix, audience, maxBodyBytes, encodedAuthorityKeys, Clock.systemUTC());
    }

    AuthzIngressFilter(String pathPrefix, String audience, int maxBodyBytes,
                       Map<String, String> encodedAuthorityKeys, Clock clock) {
        if (pathPrefix == null || !pathPrefix.startsWith("/") || pathPrefix.contains("*")
                || audience == null || !audience.matches("[a-z][a-z0-9-]{0,95}")
                || maxBodyBytes <= 0 || maxBodyBytes > AuthzContract.MAX_BODY_BYTES
                || encodedAuthorityKeys == null || encodedAuthorityKeys.isEmpty()) {
            throw new IllegalArgumentException("Invalid authorization ingress configuration");
        }
        this.pathPrefix = pathPrefix;
        this.audience = audience;
        this.maxBodyBytes = maxBodyBytes;
        Map<String, PublicKey> keys = new HashMap<>();
        encodedAuthorityKeys.forEach((id, encoded) -> {
            if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) {
                throw new IllegalArgumentException("Invalid authority key id");
            }
            keys.put(id, ServiceIdentityCrypto.decodePublicKey(encoded));
        });
        this.authorityKeys = Map.copyOf(keys);
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = path(request);
        return !(path.equals(pathPrefix) || path.startsWith(pathPrefix + "/"))
                || path.equals(pathPrefix + "/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (request.getContentLengthLong() > maxBodyBytes) throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
            byte[] body = request.getInputStream().readNBytes(maxBodyBytes + 1);
            if (body.length > maxBodyBytes) throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
            String token = bearer(request);
            AuthzResponse signed = AuthzWireCodec.decodeResponse(singleHeader(request, AuthzTransportHeaders.RESPONSE_HEADER));
            RequestBinding binding = AuthzWireCodec.decodeBinding(singleHeader(request, AuthzTransportHeaders.REQUEST_BINDING_HEADER));
            var scope = AuthzRequestScope.verifyAndOpen(signed, binding, token, audience, request.getMethod(),
                    path(request), request.getQueryString(), body, authorityKeys, clock.instant());
            try (scope) { chain.doFilter(new ReplayRequest(request, body), response); }
        } catch (AuthzException failure) {
            response.setStatus(failure.code().httpStatus());
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":false,\"code\":\"" + failure.code().name() + "\"}");
        }
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return AuthzWireCodec.validatePath(uri.substring(context.length()));
    }

    private static String bearer(HttpServletRequest request) {
        String value = singleHeader(request, "Authorization");
        if (!value.regionMatches(true, 0, "Bearer ", 0, 7)) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        String token = value.substring(7).trim();
        if (token.isEmpty() || token.length() > 16384 || token.indexOf(',') >= 0
                || token.chars().anyMatch(Character::isWhitespace)) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        }
        return token;
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.isBlank() || value.indexOf(',') >= 0) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        }
        return value;
    }

    private static final class ReplayRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private ReplayRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                private ReadListener listener;
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener value) {
                    listener = value;
                    if (listener == null) return;
                    try {
                        listener.onDataAvailable();
                        if (isFinished()) listener.onAllDataRead();
                    } catch (IOException failure) {
                        listener.onError(failure);
                    }
                }
            };
        }
        @Override public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), getCharacterEncoding() == null
                    ? java.nio.charset.StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(getCharacterEncoding())));
        }
    }
}
