package com.bkanent.interview.service;

import com.bkanent.interview.config.InterviewRuntimeProperties;
import com.bkanent.interview.entity.InterviewCaseEntity;
import com.bkanent.interview.entity.InterviewPrepCardEntity;
import com.bkanent.interview.entity.InterviewSessionEntity;
import com.bkanent.interview.mapper.InterviewCaseMapper;
import com.bkanent.interview.mapper.InterviewPrepCardMapper;
import com.bkanent.interview.mapper.InterviewQuestionMapper;
import com.bkanent.interview.mapper.InterviewSessionMapper;
import com.bkanent.interview.runtime.InterviewSessionStateMachine;
import com.bkanent.interview.security.InterviewAuthorizationScope;
import com.bkanent.interview.security.InterviewAuthzFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class InterviewPrepServiceTest {

    @Test
    void absentLegacyWorkNumberDoesNotReplaceVerifiedActorOrBreakInsert() {
        InterviewCaseMapper cases = mock(InterviewCaseMapper.class);
        InterviewPrepCardMapper cards = mock(InterviewPrepCardMapper.class);
        InterviewQuestionMapper questions = mock(InterviewQuestionMapper.class);
        InterviewSessionMapper sessions = mock(InterviewSessionMapper.class);
        InterviewParticipantService participants = mock(InterviewParticipantService.class);
        InterviewAuthorizationService authorization = mock(InterviewAuthorizationService.class);
        InterviewRuntimeProperties runtime = mock(InterviewRuntimeProperties.class);
        InterviewSessionStateMachine stateMachine = mock(InterviewSessionStateMachine.class);
        doAnswer(call -> { call.<InterviewCaseEntity>getArgument(0).setId(71L); return 1; })
                .when(cases).insert(any(InterviewCaseEntity.class));
        doAnswer(call -> { call.<InterviewPrepCardEntity>getArgument(0).setId(72L); return 1; })
                .when(cards).insert(any(InterviewPrepCardEntity.class));
        doAnswer(call -> { call.<InterviewSessionEntity>getArgument(0).setId(73L); return 1; })
                .when(sessions).insert(any(InterviewSessionEntity.class));

        InterviewPrepService service = new InterviewPrepService(cases, cards, questions, sessions,
                participants, authorization, runtime, stateMachine);
        try (var ignored = InterviewAuthorizationScope.open(InterviewAuthzFixture.response("101",
                List.of(InterviewAuthorizationService.INITIATE)))) {
            var opened = service.openCase("AGENT_DEAL", "BROKER", "active", "synthetic objective",
                    null, null, null, 15, "FORM", null, null, null);

            assertEquals(71L, opened.get("caseId"));
            assertEquals(73L, opened.get("sessionId"));
        }
        org.mockito.ArgumentCaptor<InterviewCaseEntity> caseCaptor =
                org.mockito.ArgumentCaptor.forClass(InterviewCaseEntity.class);
        org.mockito.ArgumentCaptor<InterviewPrepCardEntity> cardCaptor =
                org.mockito.ArgumentCaptor.forClass(InterviewPrepCardEntity.class);
        org.mockito.ArgumentCaptor<InterviewSessionEntity> sessionCaptor =
                org.mockito.ArgumentCaptor.forClass(InterviewSessionEntity.class);
        org.mockito.Mockito.verify(cases).insert(caseCaptor.capture());
        org.mockito.Mockito.verify(cards).insert(cardCaptor.capture());
        org.mockito.Mockito.verify(sessions).insert(sessionCaptor.capture());
        assertEquals(101L, caseCaptor.getValue().getCreatedByUserId());
        assertEquals("", caseCaptor.getValue().getCreatorWorkNo());
        assertEquals("{}", cardCaptor.getValue().getDynamicFields());
        assertEquals(101L, sessionCaptor.getValue().getCreatedByUserId());
        assertEquals("", sessionCaptor.getValue().getCreatorWorkNo());
        assertNotNull(caseCaptor.getValue().getScene());
    }
}
