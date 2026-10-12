package com.bkanent.agent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Only the real transport header supplies a token; caller metadata is never inspected. */
@Component
public class AgentRagCredentialFilter extends OncePerRequestFilter {
    private final AgentRagCredentialLeaseStore credentials;
    public AgentRagCredentialFilter(AgentRagCredentialLeaseStore credentials) { this.credentials = credentials; }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !(path.equals("/agent") || path.startsWith("/agent/"));
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try (var ignored = credentials.openAuthorization(request.getHeader("Authorization"))) {
            chain.doFilter(request, response);
        } catch (AgentRagAuthenticationException exception) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":false,\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"AUTHENTICATION_REQUIRED\",\"data\":null}");
        } catch (ServletException | IOException exception) { throw exception; }
        catch (Exception exception) { throw new ServletException("credential context cleanup failed", exception); }
    }
}
