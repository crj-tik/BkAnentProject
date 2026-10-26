package com.bkanent.common.mcp.authz;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Clock;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;

/** Protects the MCP streamable HTTP endpoint before the official server dispatches a tool call. */
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public final class McpAuthorizationFilter extends OncePerRequestFilter {
    private static final Set<String> METADATA_METHODS = Set.of(
            "initialize", "notifications/initialized", "ping", "tools/list");

    private final String endpoint;
    private final String audience;
    private final McpAuthorizationServerProperties properties;
    private final Map<String, PublicKey> authorityKeys;
    private final Map<String, PublicKey> callerKeys;
    private final McpAuthorizationReplayGuard replayGuard;
    private final Clock clock;

    public McpAuthorizationFilter(String endpoint, String audience, McpAuthorizationServerProperties properties,
                                  McpAuthorizationReplayGuard replayGuard, Clock clock) {
        this.endpoint = endpoint;
        this.audience = audience;
        this.properties = properties;
        this.authorityKeys = properties.authorityKeys();
        this.callerKeys = properties.callerKeys();
        this.replayGuard = replayGuard;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        String uri = request.getRequestURI();
        return uri == null || !uri.equals(contextPath + endpoint);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("DELETE".equalsIgnoreCase(request.getMethod())) {
            if (request.getContentLengthLong() == 0 && request.getHeader("Mcp-Session-Id") != null) {
                chain.doFilter(request, response);
            } else {
                response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            }
            return;
        }
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        String contentEncoding = request.getHeader("Content-Encoding");
        if (contentEncoding != null && !"identity".equalsIgnoreCase(contentEncoding)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        if (request.getContentLengthLong() > properties.getMaxBodyBytes()) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }

        byte[] body = request.getInputStream().readNBytes(properties.getMaxBodyBytes() + 1);
        if (body.length == 0 || body.length > properties.getMaxBodyBytes()) {
            response.sendError(body.length == 0 ? HttpServletResponse.SC_BAD_REQUEST
                    : HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }

        McpAuthorizationContract.Request parsed;
        try {
            parsed = McpAuthorizationContract.parse(body);
        } catch (AuthzException invalid) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        HttpServletRequest replayable = new ReplayableBodyRequest(request, body);
        if (METADATA_METHODS.contains(parsed.method())) {
            chain.doFilter(replayable, response);
            return;
        }
        if (!parsed.isToolCall()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        String header;
        try {
            header = singleAuthorizationHeader(request);
        } catch (AuthzException invalidHeader) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        if (properties.getPublicTools().contains(parsed.toolName())) {
            if (header != null) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            chain.doFilter(replayable, response);
            return;
        }

        String operation = properties.getToolOperations().get(parsed.toolName());
        if (operation == null || header == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        try {
            McpAuthorizationContract.Verified verified = McpAuthorizationContract.verify(header, body,
                    properties.getCallerService(), audience, parsed.toolName(), operation,
                    authorityKeys, callerKeys, clock.instant());
            replayGuard.consume(verified.callerService(), verified.audience(), verified.binding().nonce(),
                    verified.binding().expiresAtEpochSecond(), clock.instant().getEpochSecond());
            replayable.setAttribute(McpAuthorizationScope.REQUEST_ATTRIBUTE, verified);
            try (AutoCloseable ignored = McpAuthorizationScope.open(verified)) {
                chain.doFilter(replayable, response);
            } catch (ServletException | IOException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("MCP authorization scope cleanup failed", failure);
            }
        } catch (AuthzException denied) {
            response.sendError(denied.code().httpStatus());
        }
    }

    private static String singleAuthorizationHeader(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(McpAuthorizationContract.HEADER);
        if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.isBlank()
                || value.length() > McpAuthorizationContract.MAX_HEADER_CHARS) {
            throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
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
                    try {
                        if (input.available() > 0) listener.onDataAvailable();
                        if (input.available() == 0) listener.onAllDataRead();
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
