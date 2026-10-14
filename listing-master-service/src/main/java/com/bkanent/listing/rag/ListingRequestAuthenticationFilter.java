package com.bkanent.listing.rag;

import com.bkanent.common.rag.RagException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Authenticate direct HTTP business entrypoints too; static gateway markers are not credentials. */
@Component
public class ListingRequestAuthenticationFilter extends OncePerRequestFilter {
    private final ListingRagIdentity identity;

    public ListingRequestAuthenticationFilter(ListingRagIdentity identity) { this.identity = identity; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/listings") || path.startsWith("/listings/")) || path.equals("/listings/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        com.bkanent.common.rag.RagCredentialContext credentials;
        try {
            credentials = ListingRagCredentialContext.bearer(request.getHeader("Authorization"));
            identity.requireReader(credentials);
        } catch (RagException failure) {
            response.setStatus(failure.code() == com.bkanent.common.rag.RagErrorCode.FORBIDDEN ? 403
                    : failure.code() == com.bkanent.common.rag.RagErrorCode.DEPENDENCY_UNAVAILABLE ? 503 : 401);
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":false,\"code\":\"" + failure.code().name() + "\"}");
            return;
        }
        // Preserve and remove request-local transport on every exit, including controller exceptions.
        try (ListingRagCredentialContext.Scope ignored = ListingRagCredentialContext.open(credentials)) {
            chain.doFilter(request, response);
        }
    }
}
