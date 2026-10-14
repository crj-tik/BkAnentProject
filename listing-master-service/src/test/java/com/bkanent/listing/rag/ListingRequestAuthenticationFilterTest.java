package com.bkanent.listing.rag;

import com.bkanent.common.a2a.A2aCredentialContext;
import com.bkanent.common.rag.*;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListingRequestAuthenticationFilterTest {
    @Test void staticGatewayMarkerCannotAuthenticateAndDependencyFailureIs503() throws Exception {
        var identity = mock(ListingRagIdentity.class);
        var filter = new ListingRequestAuthenticationFilter(identity);
        var request = new MockHttpServletRequest("GET", "/listings/11");
        request.addHeader("X-Gateway-Authenticated", "true"); request.addHeader("X-User-Id", "42");
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        filter.doFilter(request, response, (a, b) -> called.set(true));
        assertEquals(401, response.getStatus()); assertFalse(called.get()); verifyNoInteractions(identity);
        request.addHeader("Authorization", "Bearer dependency-token");
        when(identity.requireReader(new RagCredentialContext("dependency-token")))
                .thenThrow(new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE));
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, (a, b) -> called.set(true));
        assertEquals(503, response.getStatus()); assertFalse(called.get());
    }

    @Test void realUriContextPathPropagatesCredentialsAndCleansAfterControllerFailure() {
        var identity = mock(ListingRagIdentity.class);
        var token = new RagCredentialContext("valid-token");
        when(identity.requireReader(token)).thenReturn(new AuthRagPrincipal(42L, "ADMIN", true, true, Long.MAX_VALUE));
        var filter = new ListingRequestAuthenticationFilter(identity);
        var request = new MockHttpServletRequest("GET", "/app/listings/11");
        request.setContextPath("/app"); request.addHeader("Authorization", "Bearer valid-token");
        assertThrows(ServletException.class, () -> filter.doFilter(request, new MockHttpServletResponse(), (a, b) -> {
            assertEquals(token, ListingRagCredentialContext.current());
            throw new ServletException("controller failure");
        }));
        assertThrows(RagException.class, ListingRagCredentialContext::current);
        verify(identity).requireReader(token);
    }

    @Test void absentLocalCredentialOnlyFallsBackToValidatedDedicatedA2aLease() {
        var credential = RagCredentialContext.beacon("dedicated-beacon");
        var binding = new A2aBeaconBinding(1, "request", "agent-service", "listing-master-service", "42", "POST",
                "b".repeat(64), "server-task");
        var valid = new AtomicBoolean(true);
        var lease = new A2aCredentialContext.Lease(binding, credential, Instant.now().getEpochSecond() + 60, () -> {
            if (!valid.get()) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        });
        try (var ignored = A2aCredentialContext.open(lease)) {
            assertEquals(credential, ListingRagCredentialContext.current());
            var local = new RagCredentialContext("http-token");
            try (var localScope = ListingRagCredentialContext.open(local)) { assertEquals(local, ListingRagCredentialContext.current()); }
            assertEquals(credential, ListingRagCredentialContext.current());
            valid.set(false);
            assertThrows(RagException.class, ListingRagCredentialContext::current);
        }
        assertThrows(RagException.class, ListingRagCredentialContext::current);
    }
}
