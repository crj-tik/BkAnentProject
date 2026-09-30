package com.bkanent.interview.runtime;

import com.bkanent.interview.entity.InterviewQuestionEntity;
import com.bkanent.interview.entity.InterviewSessionEntity;
import com.bkanent.interview.mapper.InterviewDirectorCommandMapper;
import com.bkanent.interview.mapper.InterviewSessionMapper;
import com.bkanent.interview.service.InterviewPrepService;
import com.bkanent.interview.service.InterviewReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InterviewControllerTest {

    private InterviewRuntimeService runtimeService;
    private InterviewSessionStateMachine stateMachine;
    private InterviewSessionMapper sessionMapper;
    private InterviewPrepService prepService;
    private InterviewDirectorCommandMapper directorCommandMapper;
    private InterviewReportService reportService;
    private InterviewController controller;

    @BeforeEach
    void setUp() {
        runtimeService = mock(InterviewRuntimeService.class);
        stateMachine = mock(InterviewSessionStateMachine.class);
        sessionMapper = mock(InterviewSessionMapper.class);
        prepService = mock(InterviewPrepService.class);
        directorCommandMapper = mock(InterviewDirectorCommandMapper.class);
        reportService = mock(InterviewReportService.class);
        controller = new InterviewController(runtimeService, stateMachine, sessionMapper,
                prepService, directorCommandMapper, reportService);
    }

    @Test
    void monitorRejectsInvalidTicketBeforeReadingSession() {
        when(stateMachine.validateTicket(12L, "bad-ticket")).thenReturn(false);

        var response = controller.monitor(12L, "bad-ticket");

        assertThat(response.success()).isFalse();
        assertThat(response.code()).isEqualTo("INTERVIEW_INVALID_TICKET");
        verifyNoInteractions(sessionMapper, prepService);
    }

    @Test
    void monitorReturnsCurrentQuestionAndProgressForValidTicket() {
        when(stateMachine.validateTicket(12L, "valid-ticket")).thenReturn(true);
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
    void startRequiresTicketAndRejectsInvalidOne() {
        when(stateMachine.validateTicket(12L, "bad-ticket")).thenReturn(false);

        var response = controller.start(12L, "bad-ticket");

        assertThat(response.success()).isFalse();
        assertThat(response.code()).isEqualTo("INTERVIEW_INVALID_TICKET");
        verifyNoInteractions(directorCommandMapper);
    }

    @Test
    void startAdvancesConfirmedSessionWithoutReissuingTicket() {
        when(stateMachine.validateTicket(12L, "form-ticket")).thenReturn(true);
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
        assertThat(response.data().get("turnsPath")).isEqualTo("/interviews/12/turns");
        // 显式开始不重签：start 不调用 issueTicket（ticket 由开台响应签发、前端一直持有）
        verifyNoInteractions(runtimeService);
    }

    @Test
    void startSurfacesIllegalTransitionAsFriendlyError() {
        when(stateMachine.validateTicket(12L, "form-ticket")).thenReturn(true);
        when(stateMachine.transition(12L, InterviewSessionStateMachine.IN_PROGRESS,
                InterviewSessionStateMachine.Actor.GOVERNANCE))
                .thenThrow(new InterviewSessionStateMachine.IllegalTransitionException(
                        "session 12 in DRAFT cannot jump to IN_PROGRESS"));

        var response = controller.start(12L, "form-ticket");

        assertThat(response.success()).isFalse();
        assertThat(response.code()).isEqualTo("INTERVIEW_START_INVALID");
    }

    @Test
    void listReportTasksDelegatesWithDefaultsAndFilters() {
        when(reportService.listCaseCardTasks("emp-01", "SUCCEEDED", 2, 50))
                .thenReturn(Map.of("tasks", List.of(), "total", 0L, "page", 2, "pageSize", 50));

        var response = controller.listReportTasks("emp-01", "SUCCEEDED", 2, 50);

        assertThat(response.success()).isTrue();
        verify(reportService).listCaseCardTasks("emp-01", "SUCCEEDED", 2, 50);
    }

    @Test
    void searchReportsDelegatesKeywordCreatorAndPaging() {
        when(reportService.searchReports("学区房", "emp-01", 1, 20))
                .thenReturn(Map.of("reports", List.of(), "total", 0L, "page", 1, "pageSize", 20));

        var response = controller.searchReports("学区房", "emp-01", 1, 20);

        assertThat(response.success()).isTrue();
        verify(reportService).searchReports("学区房", "emp-01", 1, 20);
    }

    @Test
    void reportTaskDetailMapsMissingTaskToFriendlyError() {
        when(reportService.getTaskDetail(404L)).thenReturn(Map.of("error", "task not found: 404"));

        var response = controller.reportTaskDetail(404L);

        assertThat(response.success()).isFalse();
        assertThat(response.code()).isEqualTo("INTERVIEW_TASK_NOT_FOUND");
    }
}
