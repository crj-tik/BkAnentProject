package com.bkanent.notification.mcp;

import com.bkanent.common.authz.AuthzContract;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthorizationStatus;
import com.bkanent.common.authz.DelegationBinding;
import com.bkanent.common.mcp.authz.McpAuthorizationContract;
import com.bkanent.common.mcp.authz.McpAuthorizationScope;
import com.bkanent.notification.model.NotificationMessageRequest;
import com.bkanent.notification.service.NotificationManagementService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NotificationMcpToolsAuthorizationTest {
    private static final String READ_OWN = "notification.message.read.own";
    private static final String SEND_OWN = "notification.message.send.own";
    private static final String SEND_ALL = "notification.message.send.all";
    private final NotificationManagementService service = mock(NotificationManagementService.class);
    private final NotificationMcpTools tools = new NotificationMcpTools(service);

    @Test
    void privateQueriesInjectSignedActorAndDoNotExposeModelUserId() throws Exception {
        when(service.listUserMessages(73L, null, "UNREAD")).thenReturn(List.of());
        when(service.countUnreadMessages(73L)).thenReturn(2L);

        try (var ignored = McpAuthorizationScope.open(verifiedActor("73", READ_OWN))) {
            assertThat(tools.listUserMessages("", "UNREAD")).isEmpty();
            assertThat(tools.countUnreadMessages()).isEqualTo(2L);
        }

        assertThat(NotificationMcpTools.class.getMethod("listUserMessages", String.class, String.class)).isNotNull();
        assertThat(NotificationMcpTools.class.getMethod("countUnreadMessages").getParameterCount()).isZero();
        verify(service).listUserMessages(73L, null, "UNREAD");
        verify(service).countUnreadMessages(73L);
    }

    @Test
    void guardedQueriesFailClosedWhenVerifiedTransportContextIsMissing() {
        assertThatThrownBy(() -> tools.countUnreadMessages())
                .isInstanceOf(AuthzException.class)
                .extracting(failure -> ((AuthzException) failure).code())
                .isEqualTo(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        verifyNoInteractions(service);
    }

    @Test
    void selfAndCrossRecipientSendsUseSeparateExactPermissions() throws Exception {
        when(service.sendStationMessage(any())).thenReturn(11L);

        try (var ignored = McpAuthorizationScope.open(verifiedActor("73", SEND_OWN))) {
            assertThat(tools.sendMyStationMessage("title", "body")).isEqualTo(11L);
            assertThatThrownBy(() -> tools.sendStationMessage(99L, "title", "body"))
                    .isInstanceOf(AuthzException.class)
                    .extracting(failure -> ((AuthzException) failure).code())
                    .isEqualTo(AuthzErrorCode.PERMISSION_DENIED);
        }
        try (var ignored = McpAuthorizationScope.open(verifiedActor("73", SEND_ALL))) {
            assertThat(tools.sendStationMessage(99L, "title", "body")).isEqualTo(11L);
        }

        verify(service).sendStationMessage(new NotificationMessageRequest(
                73L, null, "AGENT_WORKFLOW", "title", "body", "notification-agent"));
        verify(service).sendStationMessage(new NotificationMessageRequest(
                99L, null, "AGENT_WORKFLOW", "title", "body", "notification-agent"));
    }

    private static McpAuthorizationContract.Verified verifiedActor(String actorId, String operation) {
        long now = Instant.now().getEpochSecond();
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String hash = "a".repeat(64);
        var context = new AuthorizationContext(actorId, 9, 1, hash, List.of(operation), "agent-service",
                "request-1", "run-1", "session-1", hash, hash, nonce, now - 1, now + 120, now + 299,
                Map.of(), AuthzContract.ALGORITHM, "auth-test", null);
        var freshness = new AuthzFreshness(actorId, 9, 1, AuthorizationStatus.ACTIVE, hash,
                now - 1, now + 29, nonce, AuthzContract.ALGORITHM, "auth-test", null);
        var binding = new DelegationBinding("agent-service", "notification-service", actorId,
                List.of(operation), "run-1", null, "request-1", "b".repeat(64), hash, nonce,
                now, now + 30);
        return new McpAuthorizationContract.Verified(actorId, "agent-service", "notification-service",
                operation, "request-1", "notificationTool", context, freshness, binding);
    }
}
