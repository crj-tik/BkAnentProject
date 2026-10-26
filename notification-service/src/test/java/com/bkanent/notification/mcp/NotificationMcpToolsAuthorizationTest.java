package com.bkanent.notification.mcp;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.mcp.authz.McpAuthorizationContract;
import com.bkanent.common.mcp.authz.McpAuthorizationScope;
import com.bkanent.notification.model.NotificationMessageRequest;
import com.bkanent.notification.service.NotificationManagementService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NotificationMcpToolsAuthorizationTest {
    private final NotificationManagementService service = mock(NotificationManagementService.class);
    private final NotificationMcpTools tools = new NotificationMcpTools(service, true);

    @Test
    void privateQueriesUseTheSignedActorAndRejectDifferentModelUserIds() throws Exception {
        var proof = verifiedActor("73");
        when(service.listUserMessages(73L, null, "UNREAD")).thenReturn(List.of());
        when(service.countUnreadMessages(73L)).thenReturn(2L);

        try (var ignored = McpAuthorizationScope.open(proof)) {
            assertThat(tools.listUserMessages(null, "", "UNREAD")).isEmpty();
            assertThat(tools.countUnreadMessages(null)).isEqualTo(2L);
            assertThatThrownBy(() -> tools.listUserMessages(99L, "", ""))
                    .isInstanceOf(AuthzException.class)
                    .extracting(failure -> ((AuthzException) failure).code())
                    .isEqualTo(AuthzErrorCode.AUTHENTICATION_FAILED);
        }

        verify(service).listUserMessages(73L, null, "UNREAD");
        verify(service).countUnreadMessages(73L);
        verify(service, never()).listUserMessages(99L, null, null);
    }

    @Test
    void guardedQueriesFailClosedWhenVerifiedTransportContextIsMissing() {
        assertThatThrownBy(() -> tools.countUnreadMessages(99L))
                .isInstanceOf(AuthzException.class)
                .extracting(failure -> ((AuthzException) failure).code())
                .isEqualTo(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        verifyNoInteractions(service);
    }

    @Test
    void recipientUserIdRemainsABusinessTargetForSendOperations() throws Exception {
        when(service.sendStationMessage(any())).thenReturn(11L);
        try (var ignored = McpAuthorizationScope.open(verifiedActor("73"))) {
            assertThat(tools.sendStationMessage(99L, "title", "body")).isEqualTo(11L);
        }
        verify(service).sendStationMessage(new NotificationMessageRequest(
                99L, null, "AGENT_WORKFLOW", "title", "body", "notification-agent"));
    }

    private static McpAuthorizationContract.Verified verifiedActor(String actorId) {
        return new McpAuthorizationContract.Verified(actorId, "agent-service", "notification-service",
                "notification.message.read", "request-1", "countUnreadMessages", null, null, null);
    }
}
