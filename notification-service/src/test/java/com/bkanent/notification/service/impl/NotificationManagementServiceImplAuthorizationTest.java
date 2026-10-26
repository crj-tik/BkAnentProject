package com.bkanent.notification.service.impl;

import com.bkanent.common.agent.SessionStreamEvent;
import com.bkanent.common.authz.AuthzContract;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthorizationStatus;
import com.bkanent.common.authz.DelegationBinding;
import com.bkanent.common.mcp.authz.McpAuthorizationContract;
import com.bkanent.common.mcp.authz.McpAuthorizationScope;
import com.bkanent.notification.config.NotificationIntegrationProperties;
import com.bkanent.notification.converter.NotificationConverter;
import com.bkanent.notification.entity.NotificationRecordEntity;
import com.bkanent.notification.model.NotificationMessageRequest;
import com.bkanent.notification.model.RobotMessageRequest;
import com.bkanent.notification.service.NotificationRecordDomainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationManagementServiceImplAuthorizationTest {
    private static final String ACTOR_ID = "73";
    private static final long ACTOR = 73L;
    private static final String READ_OWN = "notification.message.read.own";
    private static final String MARK_READ_OWN = "notification.message.mark-read.own";
    private static final String SEND_OWN = "notification.message.send.own";
    private static final String SEND_ALL = "notification.message.send.all";
    private static final String EMAIL_ALL = "notification.email.send.all";
    private static final String ROBOT_ALL = "notification.robot.send.all";

    private final NotificationRecordDomainService records = mock(NotificationRecordDomainService.class);
    private final NotificationConverter converter = mock(NotificationConverter.class);
    private final NotificationIntegrationProperties integration = new NotificationIntegrationProperties();
    private final NotificationManagementServiceImpl service =
            new NotificationManagementServiceImpl(records, converter, integration);

    @BeforeEach
    void assignSavedRecordId() {
        when(records.save(any(NotificationRecordEntity.class))).thenAnswer(invocation -> {
            ((NotificationRecordEntity) invocation.getArgument(0)).setId(11L);
            return true;
        });
    }

    @Test
    void readsAndCountsUseVerifiedActor() throws Exception {
        when(records.listUserMessages(ACTOR, "STATION", "UNREAD")).thenReturn(List.of());
        when(records.countUnreadMessages(ACTOR)).thenReturn(4L);

        try (var ignored = scope(READ_OWN)) {
            assertThat(service.listUserMessages(null, "STATION", "UNREAD")).isEmpty();
            assertThat(service.countUnreadMessages(null)).isEqualTo(4L);
        }

        verify(records).listUserMessages(ACTOR, "STATION", "UNREAD");
        verify(records).countUnreadMessages(ACTOR);
    }

    @Test
    void claimedOtherUserCannotReplaceReadActor() throws Exception {
        try (var ignored = scope(READ_OWN)) {
            assertThatThrownBy(() -> service.listUserMessages(99L, null, null))
                    .isInstanceOf(AuthzException.class)
                    .extracting(error -> ((AuthzException) error).code())
                    .isEqualTo(AuthzErrorCode.PERMISSION_DENIED);
            assertThatThrownBy(() -> service.countUnreadMessages(99L))
                    .isInstanceOf(AuthzException.class)
                    .extracting(error -> ((AuthzException) error).code())
                    .isEqualTo(AuthzErrorCode.PERMISSION_DENIED);
        }
        verifyNoInteractions(records, converter);
    }

    @Test
    void markReadChecksRecordOwnerBeforeUpdate() throws Exception {
        NotificationRecordEntity entity = stationRecord(99L);
        when(records.getById(5L)).thenReturn(entity);

        try (var ignored = scope(MARK_READ_OWN)) {
            assertThatThrownBy(() -> service.markMessageRead(5L, null))
                    .isInstanceOf(AuthzException.class)
                    .extracting(error -> ((AuthzException) error).code())
                    .isEqualTo(AuthzErrorCode.PERMISSION_DENIED);
        }

        verify(records).getById(5L);
        verify(records, never()).updateById(any(NotificationRecordEntity.class));
    }

    @Test
    void markReadRejectsMismatchedClaimBeforeLoadingRecord() throws Exception {
        try (var ignored = scope(MARK_READ_OWN)) {
            assertThatThrownBy(() -> service.markMessageRead(5L, 99L))
                    .isInstanceOf(AuthzException.class)
                    .extracting(error -> ((AuthzException) error).code())
                    .isEqualTo(AuthzErrorCode.PERMISSION_DENIED);
        }
        verifyNoInteractions(records);
    }

    @Test
    void ownAndCrossRecipientStationMessagesHaveDifferentPermissions() throws Exception {
        try (var ignored = scope(SEND_OWN)) {
            assertThat(service.sendStationMessage(stationRequest(ACTOR))).isEqualTo(11L);
            assertThatThrownBy(() -> service.sendStationMessage(stationRequest(99L)))
                    .isInstanceOf(AuthzException.class)
                    .extracting(error -> ((AuthzException) error).code())
                    .isEqualTo(AuthzErrorCode.PERMISSION_DENIED);
        }

        try (var ignored = scope(SEND_ALL)) {
            assertThat(service.sendStationMessage(stationRequest(99L))).isEqualTo(11L);
        }

        verify(records, times(2)).save(any(NotificationRecordEntity.class));
    }

    @Test
    void emailAndRobotNeedTheirOwnExactPermissions() throws Exception {
        try (var ignored = scope(SEND_ALL)) {
            assertThatThrownBy(() -> service.sendEmailMessage(
                    new NotificationMessageRequest(99L, "user@example.test", "test", "title", "body", "agent")))
                    .isInstanceOf(AuthzException.class)
                    .extracting(error -> ((AuthzException) error).code())
                    .isEqualTo(AuthzErrorCode.PERMISSION_DENIED);
            assertThatThrownBy(() -> service.sendRobotMessage(
                    new RobotMessageRequest("WECOM", "https://example.test/hook", "title", "body", "agent")))
                    .isInstanceOf(AuthzException.class)
                    .extracting(error -> ((AuthzException) error).code())
                    .isEqualTo(AuthzErrorCode.PERMISSION_DENIED);
        }
        verifyNoInteractions(records);

        try (var ignored = scope(EMAIL_ALL)) {
            assertThat(service.sendEmailMessage(
                    new NotificationMessageRequest(99L, "user@example.test", "test", "title", "body", "agent")))
                    .isEqualTo(11L);
        }
        try (var ignored = scope(ROBOT_ALL)) {
            assertThat(service.sendRobotMessage(
                    new RobotMessageRequest("WECOM", "https://example.test/hook", "title", "body", "agent")))
                    .isEqualTo(11L);
        }
    }

    @Test
    void workflowEventUsesServerStampedRecipientAndRejectsMalformedIdentity() {
        SessionStreamEvent event = workflowEvent(Map.of("userId", ACTOR, "runId", "run-1"));

        assertThat(service.sendWorkflowEventMessage(event, "title", "body")).isEqualTo(11L);
        var saved = org.mockito.ArgumentCaptor.forClass(NotificationRecordEntity.class);
        verify(records).save(saved.capture());
        assertThat(saved.getValue().getRecipientUserId()).isEqualTo(ACTOR);
        assertThat(saved.getValue().getSceneCode()).isEqualTo("AGENT_WORKFLOW_EVENT");

        assertThatThrownBy(() -> service.sendWorkflowEventMessage(
                workflowEvent(Map.of("userId", 73.5, "runId", "run-1")), "title", "body"))
                .isInstanceOf(AuthzException.class)
                .extracting(error -> ((AuthzException) error).code())
                .isEqualTo(AuthzErrorCode.AUTHENTICATION_FAILED);
        assertThatThrownBy(() -> service.sendWorkflowEventMessage(
                workflowEvent(Map.of("userId", 99L, "runId", "other-run")), "title", "body"))
                .isInstanceOf(AuthzException.class)
                .extracting(error -> ((AuthzException) error).code())
                .isEqualTo(AuthzErrorCode.CONTEXT_CONFLICT);
        verify(records, times(1)).save(any(NotificationRecordEntity.class));
    }

    @Test
    void stationRequestRejectsNullAndInvalidRecipient() throws Exception {
        try (var ignored = scope(SEND_OWN)) {
            assertThatThrownBy(() -> service.sendStationMessage(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.sendStationMessage(stationRequest(0L)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(records);
    }

    private static NotificationRecordEntity stationRecord(long recipientId) {
        NotificationRecordEntity entity = new NotificationRecordEntity();
        entity.setId(5L);
        entity.setRecipientUserId(recipientId);
        entity.setChannel("STATION");
        entity.setReadStatus("UNREAD");
        return entity;
    }

    private static NotificationMessageRequest stationRequest(Long recipientId) {
        return new NotificationMessageRequest(recipientId, null, "AGENT_WORKFLOW", "title", "body", "agent");
    }

    private static SessionStreamEvent workflowEvent(Map<String, Object> metadata) {
        return new SessionStreamEvent("session-1", "run-1", "supervisor-agent", "task.completed",
                "completed", metadata, "trace-1", System.currentTimeMillis());
    }

    private static AutoCloseable scope(String operation) {
        long now = Instant.now().getEpochSecond();
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String hash = "a".repeat(64);
        var context = new AuthorizationContext(ACTOR_ID, 9, 1, hash, List.of(operation), "agent-service",
                "request-1", "run-1", "session-1", hash, hash, nonce, now - 1, now + 120, now + 299,
                Map.of(), AuthzContract.ALGORITHM, "auth-test", null);
        var freshness = new AuthzFreshness(ACTOR_ID, 9, 1, AuthorizationStatus.ACTIVE, hash,
                now - 1, now + 29, nonce, AuthzContract.ALGORITHM, "auth-test", null);
        var binding = new DelegationBinding("agent-service", "notification-service", ACTOR_ID,
                List.of(operation), "run-1", null, "request-1", "b".repeat(64), hash, nonce,
                now, now + 30);
        var verified = new McpAuthorizationContract.Verified(ACTOR_ID, "agent-service", "notification-service",
                operation, "request-1", "notificationTool", context, freshness, binding);
        return McpAuthorizationScope.open(verified);
    }
}
