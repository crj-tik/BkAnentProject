package com.bkanent.memory.controller;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.MemoryTransportHeaders;
import com.bkanent.memory.service.MemoryRagSecurity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Internal path names are not authentication. Every memory operation uses a current USER token. */
@Component
public class MemoryRequestAuthenticationFilter extends OncePerRequestFilter {
    private final MemoryRagSecurity security;
    public MemoryRequestAuthenticationFilter(MemoryRagSecurity security) { this.security = security; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/internal/memory") && !path.startsWith("/internal/memory/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ") || authorization.substring(7).isBlank()) {
                throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            }
            var principal = security.authenticate(new RagCredentialContext(authorization.substring(7)));
            String owner = request.getHeader(MemoryTransportHeaders.OWNER);
            // All internal orchestration traffic carries an owner assertion; preference/constraint endpoints
            // additionally enforce their existing owner/admin rules in the controller.
            if (owner == null || owner.isBlank()) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            security.requireOwner(principal, owner);
            request.setAttribute("memory.authenticatedPrincipal", principal);
        } catch (RagException failure) {
            response.setStatus(failure.code() == RagErrorCode.FORBIDDEN ? 403
                    : failure.code() == RagErrorCode.DEPENDENCY_UNAVAILABLE ? 503 : 401);
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":false,\"code\":\"" + failure.code().name() + "\"}");
            return;
        }
        chain.doFilter(request,response);
    }
}
