package com.bkanent.interview.runtime;

import com.bkanent.interview.entity.InterviewQuestionEntity;
import com.bkanent.interview.entity.InterviewSessionEntity;
import com.bkanent.interview.mapper.InterviewDirectorCommandMapper;
import com.bkanent.interview.mapper.InterviewSessionMapper;
import com.bkanent.interview.service.InterviewPrepService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    private InterviewController controller;

    @BeforeEach
    void setUp() {
        runtimeService = mock(InterviewRuntimeService.class);
        stateMachine = mock(InterviewSessionStateMachine.class);
        sessionMapper = mock(InterviewSessionMapper.class);
        prepService = mock(InterviewPrepService.class);
        directorCommandMapper = mock(InterviewDirectorCommandMapper.class);
        controller = new InterviewController(runtimeService, stateMachine, sessionMapper,
                prepService, directorCommandMapper);
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
}
