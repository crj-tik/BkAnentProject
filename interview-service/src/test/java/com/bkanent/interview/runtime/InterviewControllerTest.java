package com.bkanent.interview.runtime;

import com.bkanent.interview.config.InterviewAuthorizationProperties;
import com.bkanent.interview.entity.InterviewQuestionEntity;
import com.bkanent.interview.entity.InterviewSessionEntity;
import com.bkanent.interview.mapper.InterviewDirectorCommandMapper;
import com.bkanent.interview.mapper.InterviewSessionMapper;
import com.bkanent.interview.security.InterviewAuthorizationScope;
import com.bkanent.interview.security.InterviewAuthzFixture;
import com.bkanent.interview.service.InterviewAuthorizationService;
import com.bkanent.interview.service.InterviewParticipantService;
import com.bkanent.interview.service.InterviewParticipantTicketService;
import com.bkanent.interview.service.InterviewPrepService;
import com.bkanent.interview.service.InterviewReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InterviewControllerTest {

    private InterviewRuntimeService runtimeService;
    private InterviewSessionStateMachine stateMachine;
    private InterviewSessionMapper sessionMapper;
    private InterviewPrepService prepService;
    private InterviewDirectorCommandMapper directorCommandMapper;
    private InterviewReportService reportService;
    private InterviewAuthorizationService authorizationService;
    private InterviewParticipantTicketService ticketService;
    private InterviewController controller;
    private InterviewAuthorizationScope.Scope scope;

    @BeforeEach
    void setUp() {
        runtimeService = mock(InterviewRuntimeService.class);
        stateMachine = mock(InterviewSessionStateMachine.class);
        sessionMapper = mock(InterviewSessionMapper.class);
        prepService = mock(InterviewPrepService.class);
        directorCommandMapper = mock(InterviewDirectorCommandMapper.class);
        reportService = mock(InterviewReportService.class);
        authorizationService = mock(InterviewAuthorizationService.class);
        ticketService = mock(InterviewParticipantTicketService.class);
        InterviewAuthorizationProperties properties = new InterviewAuthorizationProperties();
        properties.setEnabled(true);
        controller = new InterviewController(runtimeService, stateMachine, sessionMapper,
                prepService, directorCommandMapper, reportService, mock(InterviewParticipantService.class),
                properties, authorizationService, ticketService);
        scope = InterviewAuthorizationScope.open(InterviewAuthzFixture.response("101", List.of(
                InterviewAuthorizationService.INITIATE, InterviewAuthorizationService.READ,
                InterviewAuthorizationService.RESPOND, InterviewAuthorizationService.ASSIST,
                InterviewAuthorizationService.READ_ALL)));
    }

    @AfterEach
    void clearScope() { scope.close(); }

    @Test
    void monitorRejectsInvalidCredentialBeforeReadingSession() {
        var response = controller.monitor(12L, "bad-ticket");

        assertThat(response.success()).isFalse();
        assertThat(response.code()).isEqualTo("INTERVIEW_INVALID_CREDENTIAL");
        verifyNoInteractions(sessionMapper, prepService);
        verify(ticketService).validate(12L, "bad-ticket", InterviewParticipantTicketService.READ);
    }

    @Test
    void monitorReturnsCurrentQuestionAndProgressForValidCredential() {
        when(ticketService.validate(12L, "valid-ticket", InterviewParticipantTicketService.READ))
                .thenReturn(lease(44L, 12L, 101L, InterviewParticipantTicketService.READ));
        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setId(12L);
        session.setCaseId(6L);
        session.setStatus(InterviewSessionStateMachine.IN_PROGRESS);
        session.setMode("AI_LEAD");
        session.setClosingLocked(0);
        when(sessionMapper.selectById(12L)).thenReturn(session);
        InterviewQuestionEntity question = new InterviewQuestionEntity();
        question.setId(31L);
        question.setContent("请讲讲这次交易的经过。");
        when(prepService.currentQuestion(6L)).thenReturn(question);
        when(prepService.confirmedCount(6L)).thenReturn(8L);
        when(prepService.answeredCount(6L)).thenReturn(2L);

        var response = controller.monitor(12L, "valid-ticket");

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(new InterviewMonitorResponse(
                12L, 6L, "IN_PROGRESS", "AI_LEAD", "请讲讲这次交易的经过。", 31L, 8L, 2L, false));
        verify(sessionMapper).selectById(12L);
    }

    @Test
    void startRequiresManageCredentialAndRejectsInvalidOne() {
        var response = controller.start(12L, "bad-ticket");

        assertThat(response.success()).isFalse();
        assertThat(response.code()).isEqualTo("INTERVIEW_INVALID_CREDENTIAL");
        verifyNoInteractions(directorCommandMapper);
        verify(ticketService).validate(12L, "bad-ticket", InterviewParticipantTicketService.MANAGE);
    }

    @Test
    void startAdvancesConfirmedSessionWithoutReturningCredential() {
        when(ticketService.validate(12L, "form-ticket", InterviewParticipantTicketService.MANAGE))
                .thenReturn(lease(45L, 12L, 101L, InterviewParticipantTicketService.MANAGE));
        InterviewSessionEntity started = new InterviewSessionEntity();
        started.setId(12L);
        started.setCaseId(6L);
        started.setStatus(InterviewSessionStateMachine.IN_PROGRESS);
        started.setMode("AI_LEAD");
        when(stateMachine.transition(12L, InterviewSessionStateMachine.IN_PROGRESS,
                InterviewSessionStateMachine.Actor.GOVERNANCE)).thenReturn(started);

        var response = controller.start(12L, "form-ticket");

        assertThat(response.success()).isTrue();
        assertThat(response.data().get("status")).isEqualTo("IN_PROGRESS");
        assertThat(response.data().get("credentialClaimPath")).isEqualTo("/interviews/12/credentials");
        assertThat(response.data()).doesNotContainKey("ticket");
        verifyNoInteractions(runtimeService);
    }

    @Test
    void startSurfacesIllegalTransitionAsFriendlyError() {
        when(ticketService.validate(12L, "form-ticket", InterviewParticipantTicketService.MANAGE))
                .thenReturn(lease(45L, 12L, 101L, InterviewParticipantTicketService.MANAGE));
        when(stateMachine.transition(12L, InterviewSessionStateMachine.IN_PROGRESS,
                InterviewSessionStateMachine.Actor.GOVERNANCE))
                .thenThrow(new InterviewSessionStateMachine.IllegalTransitionException(
                        "session 12 in DRAFT cannot jump to IN_PROGRESS"));

        var response = controller.start(12L, "form-ticket");

        assertThat(response.success()).isFalse();
        assertThat(response.code()).isEqualTo("INTERVIEW_START_INVALID");
    }

    @Test
    void credentialClaimReturnsNoStoreAndOnlyTheAuthenticatedClaimResponseContainsTheValue() {
        String rawCredential = "A".repeat(43);
        when(ticketService.issue(12L, InterviewParticipantTicketService.READ))
                .thenReturn(new InterviewParticipantTicketService.IssuedCredential(44L, rawCredential,
                        InterviewParticipantTicketService.READ, LocalDateTime.now().plusMinutes(10)));

        var response = controller.issueParticipantCredential(12L,
                new InterviewController.ParticipantCredentialRequest(InterviewParticipantTicketService.READ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getPragma()).contains("no-cache");
        assertThat(response.getBody().data()).containsEntry("credential", rawCredential)
                .containsEntry("headerName", "X-Ticket");
        verify(ticketService).issue(12L, InterviewParticipantTicketService.READ);
    }

    @Test
    void respondentTurnRequiresRespondPurposeBeforeCallingRuntime() {
        when(ticketService.validate(12L, "read-ticket", InterviewParticipantTicketService.RESPOND))
                .thenReturn(null);

        ServerSentEvent<String> event = controller.submitTurn(12L, "request-1", "read-ticket",
                new InterviewController.TurnRequest("回答", null)).blockFirst();

        assertThat(event).isNotNull();
        assertThat(event.event()).isEqualTo("error");
        assertThat(event.data()).isEqualTo("INTERVIEW_INVALID_CREDENTIAL");
        verify(authorizationService).requireRespondent(12L);
        verify(ticketService).validate(12L, "read-ticket", InterviewParticipantTicketService.RESPOND);
        verifyNoInteractions(runtimeService);
    }

    @Test
    void replyUsesReadPurposeAndRejectsBeforeLoadingAnySavedReply() {
        when(ticketService.validate(12L, "respond-ticket", InterviewParticipantTicketService.READ))
                .thenReturn(null);

        var response = controller.reply(12L, "request-1", "respond-ticket");

        assertThat(response.success()).isFalse();
        assertThat(response.code()).isEqualTo("INTERVIEW_INVALID_CREDENTIAL");
        verify(authorizationService).requireRead(12L);
        verify(ticketService).validate(12L, "respond-ticket", InterviewParticipantTicketService.READ);
        verifyNoInteractions(runtimeService);
    }

    @Test
    void assistanceRequiresManagePurposeBeforeLoadingOrWritingCommand() {
        when(ticketService.validate(12L, "read-ticket", InterviewParticipantTicketService.MANAGE))
                .thenReturn(null);

        var response = controller.sendDirectorCommand(12L, "read-ticket",
                new InterviewController.DirectorCommandRequest("NEXT_QUESTION", null));

        assertThat(response.success()).isFalse();
        assertThat(response.code()).isEqualTo("INTERVIEW_INVALID_CREDENTIAL");
        verify(authorizationService).requireInitiator(12L, InterviewAuthorizationService.ASSIST);
        verify(ticketService).validate(12L, "read-ticket", InterviewParticipantTicketService.MANAGE);
        verifyNoInteractions(sessionMapper, directorCommandMapper);
    }

    @Test
    void monitorStreamRejectsInvalidReadCredentialBeforeOpeningHeartbeatStream() {
        when(ticketService.validate(12L, "respond-ticket", InterviewParticipantTicketService.READ))
                .thenReturn(null);

        ServerSentEvent<String> event = controller.stream(12L, "respond-ticket").blockFirst();

        assertThat(event).isNotNull();
        assertThat(event.event()).isEqualTo("error");
        assertThat(event.data()).isEqualTo("INTERVIEW_INVALID_CREDENTIAL");
        verify(authorizationService).requireRead(12L);
        verify(ticketService).validate(12L, "respond-ticket", InterviewParticipantTicketService.READ);
        verifyNoInteractions(sessionMapper, prepService);
    }

    @Test
    void reportTaskListsAreScopedBeforePagination() {
        when(authorizationService.visibleCaseIds()).thenReturn(List.of(6L));
        when(reportService.listCaseCardTasks("emp-01", "SUCCEEDED", 2, 50, List.of(6L)))
                .thenReturn(Map.of("tasks", List.of(), "total", 0L, "page", 2, "pageSize", 50));

        var response = controller.listReportTasks("emp-01", "SUCCEEDED", 2, 50);

        assertThat(response.success()).isTrue();
        verify(reportService).listCaseCardTasks("emp-01", "SUCCEEDED", 2, 50, List.of(6L));
    }

    @Test
    void searchReportsAreScopedBeforePagination() {
        when(authorizationService.visibleCaseIds()).thenReturn(List.of(6L));
        when(reportService.searchReports("学区房", "emp-01", 1, 20, List.of(6L)))
                .thenReturn(Map.of("reports", List.of(), "total", 0L, "page", 1, "pageSize", 20));

        var response = controller.searchReports("学区房", "emp-01", 1, 20);

        assertThat(response.success()).isTrue();
        verify(reportService).searchReports("学区房", "emp-01", 1, 20, List.of(6L));
    }

    @Test
    void reportTaskDetailHidesUnknownIds() {
        when(authorizationService.visibleCaseIds()).thenReturn(List.of(6L));
        when(reportService.getTaskDetail(404L, List.of(6L))).thenReturn(Map.of("error", "task not found: 404"));

        var response = controller.reportTaskDetail(404L);

        assertThat(response.success()).isFalse();
        assertThat(response.code()).isEqualTo("INTERVIEW_TASK_NOT_FOUND");
    }

    private InterviewParticipantTicketService.CredentialLease lease(Long id, Long sessionId, Long actorId,
                                                                     String purpose) {
        return new InterviewParticipantTicketService.CredentialLease(id, sessionId, actorId,
                purpose, "a".repeat(64), 1, LocalDateTime.now().plusMinutes(10));
    }
}
