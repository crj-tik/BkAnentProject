package com.bkanent.listing.rag;

import com.bkanent.common.a2a.A2aCredentialContext;
import com.bkanent.common.rag.*;
import com.bkanent.listing.controller.ListingRagController;
import com.bkanent.listing.controller.ListingRagExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ListingRequestAuthenticationFilterTest {
    private static final String SEARCH_JSON = "{\"contractVersion\":1,\"requestId\":\"http-test\",\"query\":\"offline listing query\"}";
    private static final RagCredentialContext HTTP_CREDENTIAL = new RagCredentialContext("valid-token");

    @Test void controllerAdviceMapsEverySafeRagCodeWithoutReturningSuccessOrProviderDetails() throws Exception {
        for (RagErrorCode code : RagErrorCode.values()) {
            var identity = authenticatedIdentity();
            var facade = mock(ListingRagFacade.class);
            RagException failure = new RagException(code, "spaceId");
            when(facade.search(any(RagListingSearchRequest.class), eq(HTTP_CREDENTIAL), isNull())).thenThrow(failure);
            var mvc = ragMvc(identity, facade);
            var result = mvc.perform(post("/listings/rag/search").header("Authorization", "Bearer valid-token")
                            .contentType(MediaType.APPLICATION_JSON).content(SEARCH_JSON))
                    .andExpect(status().is(expectedStatus(code))).andReturn();
            var body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
            assertEquals(4, body.size());
            assertFalse(body.get("success").asBoolean());
            assertEquals(code.name(), body.get("code").asText());
            assertEquals(code.name(), body.get("message").asText());
            assertEquals(1, body.get("data").size());
            assertEquals("spaceId", body.get("data").get("field").asText());
            assertSame(failure, result.getResolvedException());
            verify(facade).search(any(RagListingSearchRequest.class), eq(HTTP_CREDENTIAL), isNull());
            verify(identity).requireReader(HTTP_CREDENTIAL);
            assertThrows(RagException.class, ListingRagCredentialContext::current);
        }
    }

    @Test void controllerAdviceDoesNotSerializeCauseSuppressedOrUnsafeField() throws Exception {
        var identity = authenticatedIdentity();
        var facade = mock(ListingRagFacade.class);
        RagException failure = new RagException(RagErrorCode.SPACE_NOT_READY, "access-token-MUST-NOT-ECHO");
        failure.initCause(new IllegalStateException("UNTRUSTED_PROVIDER_MESSAGE https://private.invalid/secret"));
        failure.addSuppressed(new RuntimeException("UNTRUSTED_SUPPRESSED_CREDENTIAL"));
        when(facade.search(any(RagListingSearchRequest.class), eq(HTTP_CREDENTIAL), isNull())).thenThrow(failure);
        var result = ragMvc(identity, facade).perform(post("/listings/rag/search")
                        .header("Authorization", "Bearer valid-token").contentType(MediaType.APPLICATION_JSON).content(SEARCH_JSON))
                .andExpect(status().isServiceUnavailable()).andReturn();
        String output = result.getResponse().getContentAsString();
        var body = new ObjectMapper().readTree(output);
        assertFalse(body.get("success").asBoolean());
        assertEquals("SPACE_NOT_READY", body.get("code").asText());
        assertEquals("SPACE_NOT_READY", body.get("message").asText());
        assertTrue(body.get("data").isNull());
        assertEquals(4, body.size());
        assertFalse(output.contains("UNTRUSTED"));
        assertFalse(output.contains("private.invalid"));
        assertFalse(output.contains("access-token"));
        assertFalse(output.contains("cause"));
        assertFalse(output.contains("stackTrace"));
        assertThrows(RagException.class, ListingRagCredentialContext::current);
    }

    @Test void successfulControllerResponseKeepsOriginalApiEnvelopeAndCredentials() throws Exception {
        var identity = authenticatedIdentity();
        var facade = mock(ListingRagFacade.class);
        var response = new RagListingSearchResponse(1, "http-test", List.of(), "", null);
        when(facade.search(any(RagListingSearchRequest.class), eq(HTTP_CREDENTIAL), isNull())).thenAnswer(invocation -> {
            assertEquals(HTTP_CREDENTIAL, ListingRagCredentialContext.current());
            return response;
        });
        var result = ragMvc(identity, facade).perform(post("/listings/rag/search")
                        .header("Authorization", "Bearer valid-token").contentType(MediaType.APPLICATION_JSON).content(SEARCH_JSON))
                .andExpect(status().isOk()).andReturn();
        var body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertTrue(body.get("success").asBoolean());
        assertEquals("0", body.get("code").asText());
        assertEquals("OK", body.get("message").asText());
        assertEquals("http-test", body.get("data").get("requestId").asText());
        assertEquals(0, body.get("data").get("matches").size());
        verify(facade).search(any(RagListingSearchRequest.class), eq(HTTP_CREDENTIAL), isNull());
        assertThrows(RagException.class, ListingRagCredentialContext::current);
    }

    @Test void missingHttpCredentialStillStopsBeforeController() throws Exception {
        var identity = mock(ListingRagIdentity.class);
        var facade = mock(ListingRagFacade.class);
        var result = ragMvc(identity, facade).perform(post("/listings/rag/search")
                        .contentType(MediaType.APPLICATION_JSON).content(SEARCH_JSON))
                .andExpect(status().isUnauthorized()).andReturn();
        var body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertFalse(body.get("success").asBoolean());
        assertEquals("AUTHENTICATION_FAILED", body.get("code").asText());
        verifyNoInteractions(identity, facade);
        assertThrows(RagException.class, ListingRagCredentialContext::current);
    }

    @Test void adviceHandlesRagExceptionsGloballyButNotUnrelatedRuntimeFailures() throws Exception {
        var identity = authenticatedIdentity();
        var facade = mock(ListingRagFacade.class);
        var failure = new IllegalStateException("UNTRUSTED_PROVIDER_MESSAGE");
        when(facade.search(any(RagListingSearchRequest.class), eq(HTTP_CREDENTIAL), isNull())).thenThrow(failure);
        ServletException raised = assertThrows(ServletException.class, () -> ragMvc(identity, facade).perform(
                post("/listings/rag/search").header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON).content(SEARCH_JSON)));
        assertSame(failure, raised.getCause());
        assertThrows(RagException.class, ListingRagCredentialContext::current);

        // Global advice (3.3 follow-up): business controllers map RagException to its public HTTP
        // status too — the previous 500-on-FORBIDDEN behavior on /listings is closed.
        var other = MockMvcBuilders.standaloneSetup(new UnrelatedController())
                .setControllerAdvice(new ListingRagExceptionHandler()).build();
        var result = other.perform(get("/unrelated-failure")).andReturn();
        assertEquals(503, result.getResponse().getStatus());
        assertEquals("SPACE_NOT_READY", com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result.getResponse().getContentAsString()).path("code").asText());
    }

    private static ListingRagIdentity authenticatedIdentity() {
        var identity = mock(ListingRagIdentity.class);
        when(identity.requireReader(HTTP_CREDENTIAL))
                .thenReturn(new AuthRagPrincipal(42L, "ADMIN", true, true, Long.MAX_VALUE));
        return identity;
    }

    private static MockMvc ragMvc(ListingRagIdentity identity, ListingRagFacade facade) {
        return MockMvcBuilders.standaloneSetup(new ListingRagController(facade, mock(ListingRagGrantService.class),
                        mock(ListingRagBackfillService.class), mock(ListingRagDispatcher.class)))
                .setControllerAdvice(new ListingRagExceptionHandler())
                .addFilters(new ListingRequestAuthenticationFilter(identity)).build();
    }

    private static int expectedStatus(RagErrorCode code) {
        return switch (code) {
            case AUTHENTICATION_FAILED, AUTHENTICATION_EXPIRED -> 401;
            case FORBIDDEN, ENDPOINT_POLICY_DENIED -> 403;
            case INVALID_ARGUMENT, RESOURCE_SCOPE_TOO_LARGE -> 400;
            case NOT_FOUND -> 404;
            case INDEX_CONFLICT -> 409;
            case TIMEOUT -> 504;
            case SPACE_NOT_READY, DEPENDENCY_UNAVAILABLE, RERANK_PROTOCOL_ERROR, SCORING_PROFILE_MISMATCH -> 503;
        };
    }

    @RestController
    public static class UnrelatedController {
        @GetMapping("/unrelated-failure") public void fail() { throw new RagException(RagErrorCode.SPACE_NOT_READY); }
    }

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
