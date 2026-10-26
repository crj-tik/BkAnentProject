package com.bkanent.interview.security;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzResponse;
import com.bkanent.common.authz.AuthzTransportHeaders;
import com.bkanent.common.authz.AuthzWireCodec;
import com.bkanent.common.authz.RequestBinding;
import com.bkanent.interview.config.InterviewAuthorizationProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.net.URLDecoder;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies the Gateway-signed request locally before Interview parses or executes it. */
@Component
public class InterviewAuthorizationFilter extends OncePerRequestFilter {
    private static final String AUDIENCE = "interview-service";
    private static final Set<String> CREDENTIAL_QUERY_KEYS = Set.of(
            "ticket", "session_ticket", "participant_ticket", "access_token", "refresh_token",
            "authorization", "authz", "proof", "request_proof", "credential");
    private final InterviewAuthorizationProperties properties;
    private final Map<String, PublicKey> authorityKeys;

    public InterviewAuthorizationFilter(InterviewAuthorizationProperties properties) {
        this.properties = properties;
        properties.validate();
        this.authorityKeys = properties.verificationKeys();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/interviews") || path.startsWith("/interviews/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (containsCredentialQueryKey(request.getQueryString())) {
            reject(response, AuthzErrorCode.INVALID_ARGUMENT.httpStatus());
            return;
        }
        if (!properties.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        AuthzResponse signed;
        ReplayableBodyRequest replayable;
        try {
            String responseHeader = singleHeader(request, AuthzTransportHeaders.RESPONSE_HEADER);
            String bindingHeader = singleHeader(request, AuthzTransportHeaders.REQUEST_BINDING_HEADER);
            String token = accessToken(request);
            byte[] body = readBounded(request);
            signed = AuthzWireCodec.decodeResponse(responseHeader);
            RequestBinding binding = AuthzWireCodec.decodeBinding(bindingHeader);
            String path = request.getRequestURI().substring(request.getContextPath().length());
            List<RequestBinding.QueryParameter> query = AuthzWireCodec.parseQuery(request.getQueryString());
            AuthzWireCodec.verifyIngress(signed, binding, token, AUDIENCE, request.getMethod(), path,
                    query, body, authorityKeys, Instant.now());
            replayable = new ReplayableBodyRequest(request, body);
        } catch (AuthzException | IllegalArgumentException invalid) {
            reject(response, invalid instanceof AuthzException authz
                    ? authz.code().httpStatus() : AuthzErrorCode.AUTHENTICATION_FAILED.httpStatus());
            return;
        }
        try (InterviewAuthorizationScope.Scope ignored = InterviewAuthorizationScope.open(signed)) {
            chain.doFilter(replayable, response);
        }
    }

    private static boolean containsCredentialQueryKey(String queryString) {
        if (queryString == null || queryString.isBlank()) return false;
        try {
            for (String pair : queryString.split("&", -1)) {
                String key = pair.split("=", 2)[0];
                if (CREDENTIAL_QUERY_KEYS.contains(URLDecoder.decode(key, StandardCharsets.UTF_8)
                        .toLowerCase(java.util.Locale.ROOT))) return true;
            }
            return false;
        } catch (IllegalArgumentException malformed) {
            return true;
        }
    }

    private static void reject(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"code\":\"AUTHORIZATION_CONTEXT_INVALID\"}");
    }

    private byte[] readBounded(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > properties.getMaxBodyBytes()) {
            throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
        }
        byte[] body = request.getInputStream().readNBytes(properties.getMaxBodyBytes() + 1);
        if (body.length > properties.getMaxBodyBytes()) throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
        return body;
    }

    private static String accessToken(HttpServletRequest request) {
        String value = singleHeader(request, "Authorization");
        if (value == null || value.isBlank()) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        String token = value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : value.trim();
        if (token.isBlank()) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        return token;
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.isBlank() || value.contains(",")) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        }
        return value;
    }

    private static final class ReplayableBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private ReplayableBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    if (listener == null) return;
                    try {
                        if (isFinished()) listener.onAllDataRead();
                        else listener.onDataAvailable();
                    } catch (IOException failure) {
                        listener.onError(failure);
                    }
                }
            };
        }

        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
