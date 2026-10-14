package com.bkanent.agent.security;

import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.common.rpc.AuthPrincipalContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentPrincipalContextTest {

    @Test
    void bindsSupervisorIdentityAndContextToGatewayPrincipal() {
        AgentPrincipalContext principal = principal("42");
        SupervisorTaskRequest request = new SupervisorTaskRequest(
                null, null, "task", "trace", "hello", Map.of("userId", "999", "key", "value"), "web", true);

        SupervisorTaskRequest bound = principal.bind(request);

        assertThat(bound.userId()).isEqualTo("42");
        assertThat(bound.sessionId()).isNotBlank();
        assertThat(bound.context()).containsEntry("userId", "42").containsEntry("key", "value");
    }

    @Test
    void rejectsCallerSuppliedDifferentUserId() {
        AgentPrincipalContext principal = principal("42");

        assertThatThrownBy(() -> principal.resolveUserId("999"))
                .isInstanceOf(AgentPrincipalException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void rejectsPrincipalHeadersNotMarkedAsGatewayInjected() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AuthPrincipalContext.USER_ID_HEADER, "42");
        request.addHeader(AuthPrincipalContext.MARKER_HEADER, "client");

        assertThatThrownBy(() -> new AgentPrincipalContext(request, org.mockito.Mockito.mock(com.bkanent.common.rpc.AuthPermissionRpcService.class)))
                .isInstanceOf(AgentPrincipalException.class)
                .hasMessageContaining("gateway principal");
    }

    @Test
    void gatewayMarkerAloneCannotAuthenticateAndTokenMustMatchOwner() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AuthPrincipalContext.USER_ID_HEADER, "42");
        request.addHeader(AuthPrincipalContext.MARKER_HEADER, AuthPrincipalContext.MARKER_VALUE);
        var auth = org.mockito.Mockito.mock(com.bkanent.common.rpc.AuthPermissionRpcService.class);
        assertThatThrownBy(() -> new AgentPrincipalContext(request, auth)).isInstanceOf(AgentPrincipalException.class);
        request.addHeader("Authorization", "Bearer other-token");
        org.mockito.Mockito.when(auth.resolvePrincipal("other-token")).thenReturn(new com.bkanent.common.rpc.AuthenticatedPrincipal(7L));
        assertThatThrownBy(() -> new AgentPrincipalContext(request, auth)).isInstanceOf(AgentPrincipalException.class);
        org.mockito.Mockito.when(auth.resolvePrincipal("other-token")).thenReturn(null);
        assertThatThrownBy(() -> new AgentPrincipalContext(request, auth)).isInstanceOf(AgentPrincipalException.class);
    }

    private AgentPrincipalContext principal(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AuthPrincipalContext.USER_ID_HEADER, userId);
        request.addHeader(AuthPrincipalContext.MARKER_HEADER, AuthPrincipalContext.MARKER_VALUE);
        request.addHeader("Authorization", "Bearer real-token");
        var auth = org.mockito.Mockito.mock(com.bkanent.common.rpc.AuthPermissionRpcService.class);
        org.mockito.Mockito.when(auth.resolvePrincipal("real-token")).thenReturn(
                new com.bkanent.common.rpc.AuthenticatedPrincipal(Long.parseLong(userId)));
        return new AgentPrincipalContext(request, auth);
    }
}
