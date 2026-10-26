package com.bkanent.interview.runtime;

import com.bkanent.interview.config.InterviewAuthorizationProperties;
import com.bkanent.interview.entity.InterviewSessionEntity;
import com.bkanent.interview.mapper.InterviewDirectorCommandMapper;
import com.bkanent.interview.mapper.InterviewSessionMapper;
import com.bkanent.interview.security.InterviewAuthzFixture;
import com.bkanent.interview.security.InterviewAuthorizationScope;
import com.bkanent.interview.service.InterviewAuthorizationService;
import com.bkanent.interview.service.InterviewParticipantService;
import com.bkanent.interview.service.InterviewPrepService;
import com.bkanent.interview.service.InterviewReportService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InterviewControllerAuthorizationTest {
    @Test
    void openingUsesSignedActorAndDoesNotTreatWorkNumberOrRespondentAsOwner() {
        InterviewRuntimeService runtime = mock(InterviewRuntimeService.class);
        InterviewSessionStateMachine stateMachine = mock(InterviewSessionStateMachine.class);
        InterviewSessionMapper sessions = mock(InterviewSessionMapper.class);
        InterviewPrepService prep = mock(InterviewPrepService.class);
        InterviewDirectorCommandMapper commands = mock(InterviewDirectorCommandMapper.class);
        InterviewReportService reports = mock(InterviewReportService.class);
        InterviewParticipantService participants = mock(InterviewParticipantService.class);
        InterviewAuthorizationProperties properties = new InterviewAuthorizationProperties();
        properties.setEnabled(true);
        InterviewController controller = new InterviewController(runtime, stateMachine, sessions, prep, commands,
                reports, participants, properties, mock(InterviewAuthorizationService.class));
        when(prep.openCase(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(Map.of("sessionId", 12L));
        var request = new InterviewController.OpenCaseRequest("SECOND_HAND_PARTY", "OWNER", "objective",
                "division", "region", "district", "active", 30, "MODEL-SUPPLIED-WORKNO", 202L);

        try (var ignored = InterviewAuthorizationScope.open(InterviewAuthzFixture.response("101",
                List.of(InterviewAuthorizationService.INITIATE)))) {
            var response = controller.openCase(request);
            assertTrue(response.success());
        }

        verify(prep).openCase("SECOND_HAND_PARTY", "OWNER", "active", "objective", "division", "region",
                "district", 30, "FORM", "MODEL-SUPPLIED-WORKNO", 101L, 202L);
    }

    @Test
    void forgedOrMissingInitiateOperationStopsBeforeOpening() {
        InterviewPrepService prep = mock(InterviewPrepService.class);
        InterviewAuthorizationProperties properties = new InterviewAuthorizationProperties();
        properties.setEnabled(true);
        InterviewController controller = new InterviewController(mock(InterviewRuntimeService.class),
                mock(InterviewSessionStateMachine.class), mock(InterviewSessionMapper.class), prep,
                mock(InterviewDirectorCommandMapper.class), mock(InterviewReportService.class),
                mock(InterviewParticipantService.class), properties,
                mock(InterviewAuthorizationService.class));
        var request = new InterviewController.OpenCaseRequest("SECOND_HAND_PARTY", "OWNER", "objective",
                "division", "region", "district", "active", 30, "forged", 202L);

        try (var ignored = InterviewAuthorizationScope.open(InterviewAuthzFixture.response("999",
                List.of(InterviewAuthorizationService.READ)))) {
            var response = controller.openCase(request);
            assertFalse(response.success());
        }

        verifyNoInteractions(prep);
    }

    @Test
    void newAuthorizationPathDoesNotUseLegacyTicketAsRespondentProof() {
        InterviewRuntimeService runtime = mock(InterviewRuntimeService.class);
        InterviewParticipantService participants = mock(InterviewParticipantService.class);
        when(participants.hasConfirmedRelation(12L, 101L, InterviewParticipantService.RESPOND)).thenReturn(false);
        InterviewAuthorizationProperties properties = new InterviewAuthorizationProperties();
        properties.setEnabled(true);
        InterviewAuthorizationService authorization = new InterviewAuthorizationService(properties,
                mock(InterviewSessionMapper.class), participants);
        InterviewController controller = new InterviewController(runtime,
                mock(InterviewSessionStateMachine.class), mock(InterviewSessionMapper.class),
                mock(InterviewPrepService.class), mock(InterviewDirectorCommandMapper.class),
                mock(InterviewReportService.class), participants, properties, authorization);

        try (var ignored = InterviewAuthorizationScope.open(InterviewAuthzFixture.response("101",
                List.of(InterviewAuthorizationService.INITIATE)))) {
            var reply = controller.submitTurn(12L, "request-1", "valid-old-ticket",
                    new InterviewController.TurnRequest("answer", null)).collectList().block();
            assertNotNull(reply);
            assertEquals("error", reply.get(0).event());
        }

        verifyNoInteractions(runtime);
    }
}
