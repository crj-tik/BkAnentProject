package com.bkanent.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bkanent.interview.config.InterviewRuntimeProperties;
import com.bkanent.interview.entity.InterviewCaseEntity;
import com.bkanent.interview.entity.InterviewQuestionEntity;
import com.bkanent.interview.entity.InterviewSessionEntity;
import com.bkanent.interview.mapper.InterviewCaseMapper;
import com.bkanent.interview.mapper.InterviewPrepCardMapper;
import com.bkanent.interview.mapper.InterviewQuestionMapper;
import com.bkanent.interview.mapper.InterviewSessionMapper;
import com.bkanent.interview.runtime.InterviewSessionStateMachine;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InterviewPrepAuthorizationTest {
    @BeforeAll
    static void initializeLambdaMetadata() {
        Configuration configuration = new Configuration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "prep-auth-test");
        TableInfoHelper.initTableInfo(assistant, InterviewCaseEntity.class);
        TableInfoHelper.initTableInfo(assistant, InterviewSessionEntity.class);
        TableInfoHelper.initTableInfo(assistant, InterviewQuestionEntity.class);
    }

    @Test
    void questionMutationAfterStartIsRejectedBeforeQuestionQueriesOrWrites() {
        InterviewCaseMapper cases = mock(InterviewCaseMapper.class);
        InterviewSessionMapper sessions = mock(InterviewSessionMapper.class);
        InterviewQuestionMapper questions = mock(InterviewQuestionMapper.class);
        InterviewAuthorizationService authorization = mock(InterviewAuthorizationService.class);
        InterviewPrepService service = new InterviewPrepService(cases, mock(InterviewPrepCardMapper.class),
                questions, sessions, mock(InterviewParticipantService.class), authorization,
                new InterviewRuntimeProperties(), mock(InterviewSessionStateMachine.class));
        when(cases.selectById(6L)).thenReturn(new InterviewCaseEntity());
        InterviewSessionEntity running = new InterviewSessionEntity();
        running.setId(12L);
        running.setCaseId(6L);
        running.setStatus(InterviewSessionStateMachine.IN_PROGRESS);
        when(sessions.selectOne(any())).thenReturn(running);

        assertThrows(IllegalArgumentException.class, () -> service.createCandidateQuestions(6L,
                "T1", List.of(InterviewPrepService.QuestionDraft.of("题目", "目标"))));

        verifyNoInteractions(questions);
        InOrder order = inOrder(authorization, cases, sessions);
        order.verify(authorization).requireOperation(InterviewAuthorizationService.INITIATE);
        order.verify(cases).selectById(6L);
        order.verify(sessions).selectOne(any());
        order.verify(authorization).requireInitiator(12L, InterviewAuthorizationService.INITIATE);
    }

    @Test
    void missingInitiatePermissionIsRejectedBeforeCaseOrSessionLookup() {
        InterviewCaseMapper cases = mock(InterviewCaseMapper.class);
        InterviewSessionMapper sessions = mock(InterviewSessionMapper.class);
        InterviewQuestionMapper questions = mock(InterviewQuestionMapper.class);
        InterviewAuthorizationService authorization = mock(InterviewAuthorizationService.class);
        InterviewPrepService service = new InterviewPrepService(cases, mock(InterviewPrepCardMapper.class),
                questions, sessions, mock(InterviewParticipantService.class), authorization,
                new InterviewRuntimeProperties(), mock(InterviewSessionStateMachine.class));
        doThrow(new com.bkanent.common.authz.AuthzException(
                com.bkanent.common.authz.AuthzErrorCode.PERMISSION_DENIED))
                .when(authorization).requireOperation(InterviewAuthorizationService.INITIATE);

        assertThrows(com.bkanent.common.authz.AuthzException.class, () -> service.createCandidateQuestions(6L,
                "T1", List.of(InterviewPrepService.QuestionDraft.of("题目", "目标"))));

        verifyNoInteractions(cases, sessions, questions);
    }

    @Test
    void confirmationWithForeignQuestionIdMakesNoPartialUpdates() {
        InterviewCaseMapper cases = mock(InterviewCaseMapper.class);
        InterviewSessionMapper sessions = mock(InterviewSessionMapper.class);
        InterviewQuestionMapper questions = mock(InterviewQuestionMapper.class);
        InterviewAuthorizationService authorization = mock(InterviewAuthorizationService.class);
        InterviewPrepService service = new InterviewPrepService(cases, mock(InterviewPrepCardMapper.class),
                questions, sessions, mock(InterviewParticipantService.class), authorization,
                new InterviewRuntimeProperties(), mock(InterviewSessionStateMachine.class));
        when(cases.selectById(6L)).thenReturn(new InterviewCaseEntity());
        InterviewSessionEntity draft = new InterviewSessionEntity();
        draft.setId(12L);
        draft.setCaseId(6L);
        draft.setStatus(InterviewSessionStateMachine.DRAFT);
        when(sessions.selectOne(any())).thenReturn(draft);
        InterviewQuestionEntity existing = new InterviewQuestionEntity();
        existing.setId(31L);
        existing.setCaseId(6L);
        when(questions.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existing));

        assertThrows(IllegalArgumentException.class, () -> service.confirmQuestions(6L, List.of(31L, 99L)));

        verify(questions, never()).updateById(any(InterviewQuestionEntity.class));
        verify(authorization).requireOperation(InterviewAuthorizationService.INITIATE);
        verify(authorization).requireInitiator(12L, InterviewAuthorizationService.INITIATE);
        verifyNoMoreInteractions(authorization);
    }
}
