package com.bkanent.interview.security;

import com.bkanent.interview.config.InterviewAuthorizationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class InterviewAuthorizationFilterTest {
    @Test
    void rejectsCredentialsInUrlEvenWhilePrivateAuthorizationIsDisabled() throws Exception {
        InterviewAuthorizationFilter filter = new InterviewAuthorizationFilter(new InterviewAuthorizationProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/interviews/12/stream");
        request.setQueryString("%74icket=secret-value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertEquals(400, response.getStatus());
        assertFalse(continued.get());
    }

    @Test
    void disabledAuthorizationStillPassesNonCredentialRequestsToController() throws Exception {
        InterviewAuthorizationFilter filter = new InterviewAuthorizationFilter(new InterviewAuthorizationProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/interviews/12");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertTrue(continued.get());
    }
}
