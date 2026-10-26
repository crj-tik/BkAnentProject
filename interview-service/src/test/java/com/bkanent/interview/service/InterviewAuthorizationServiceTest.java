package com.bkanent.interview.service;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.interview.config.InterviewAuthorizationProperties;
import com.bkanent.interview.entity.InterviewDirectorCommandEntity;
import com.bkanent.interview.mapper.InterviewParticipantMapper;
import com.bkanent.interview.mapper.InterviewSessionMapper;
import com.bkanent.interview.security.InterviewAuthzFixture;
import com.bkanent.interview.security.InterviewAuthorizationScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InterviewAuthorizationServiceTest {
    @Test
    void signedActorIsUsedForCreatorRelationInsteadOfModelSuppliedIdentity() {
        var fixture = fixture();
        var service = fixture.service();
        when(fixture.participants().hasConfirmedRelation(12L, 101L, InterviewParticipantService.CREATE))
                .thenReturn(true);

        try (var ignored = InterviewAuthorizationScope.open(InterviewAuthzFixture.response("101",
                List.of(InterviewAuthorizationService.INITIATE)))) {
            service.requireInitiator(12L, InterviewAuthorizationService.INITIATE);
        }

        verify(fixture.participants()).hasConfirmedRelation(12L, 101L, InterviewParticipantService.CREATE);
        verifyNoMoreInteractions(fixture.participants());
    }

    @Test
    void respondentMayReadOwnSessionWithoutInitiatePermission() {
        var fixture = fixture();
        var service = fixture.service();
        when(fixture.participants().hasConfirmedAnyRelation(12L, 202L)).thenReturn(true);

        try (var ignored = InterviewAuthorizationScope.open(InterviewAuthzFixture.response("202",
                List.of(InterviewAuthorizationService.RESPOND)))) {
            service.requireRead(12L);
        }

        verify(fixture.participants()).hasConfirmedAnyRelation(12L, 202L);
    }

    @Test
    void businessWideReadIsExplicitAndDoesNotGrantMemoryAccess() {
        var fixture = fixture();
        var service = fixture.service();
        try (var ignored = InterviewAuthorizationScope.open(InterviewAuthzFixture.response("303",
                List.of(InterviewAuthorizationService.READ_ALL)))) {
            service.requireRead(12L);
            assertNull(service.visibleCaseIds());
            AuthzException error = assertThrows(AuthzException.class,
                    () -> InterviewAuthorizationScope.requirePermission("memory.read"));
            assertEquals(AuthzErrorCode.PERMISSION_DENIED, error.code());
        }

        verifyNoInteractions(fixture.participants());
    }

    @Test
    void businessWideReadDoesNotBypassCreatorRelationshipForWrites() {
        var fixture = fixture();
        when(fixture.participants().hasConfirmedRelation(12L, 303L, InterviewParticipantService.CREATE))
                .thenReturn(false);

        try (var ignored = InterviewAuthorizationScope.open(InterviewAuthzFixture.response("303",
                List.of(InterviewAuthorizationService.READ_ALL, InterviewAuthorizationService.INITIATE)))) {
            AuthzException denied = assertThrows(AuthzException.class,
                    () -> fixture.service().requireInitiator(12L, InterviewAuthorizationService.INITIATE));
            assertEquals(AuthzErrorCode.PERMISSION_DENIED, denied.code());
        }
    }

    @Test
    void deferredEndCommandStoresTheExactAuthorizedOperationAndActor() {
        var fixture = fixture();
        when(fixture.participants().hasConfirmedRelation(12L, 101L, InterviewParticipantService.CREATE))
                .thenReturn(true);
        InterviewDirectorCommandEntity command = new InterviewDirectorCommandEntity();
        command.setSessionId(12L);
        command.setCommand("END_SESSION");

        try (var ignored = InterviewAuthorizationScope.open(InterviewAuthzFixture.response("101",
                List.of(InterviewAuthorizationService.END)))) {
            fixture.service().stampAuthorizedCommand(command);
        }

        assertEquals(101L, command.getRequestedByUserId());
        assertEquals(InterviewAuthorizationService.END, command.getAuthorizationOperation());
        assertNotNull(command.getAuthorizationExpiresAt());
    }

    @Test
    void unrelatedOrdinaryUserCannotReadBySessionId() {
        var fixture = fixture();
        var service = fixture.service();
        when(fixture.participants().hasConfirmedAnyRelation(12L, 404L)).thenReturn(false);

        try (var ignored = InterviewAuthorizationScope.open(InterviewAuthzFixture.response("404",
                List.of(InterviewAuthorizationService.READ)))) {
            AuthzException error = assertThrows(AuthzException.class, () -> service.requireRead(12L));
            assertEquals(AuthzErrorCode.PERMISSION_DENIED, error.code());
        }
    }

    private static Fixture fixture() {
        InterviewParticipantService participants = mock(InterviewParticipantService.class);
        return new Fixture(new InterviewAuthorizationService(enabled(), mock(InterviewSessionMapper.class),
                participants), participants);
    }

    private static InterviewAuthorizationProperties enabled() {
        InterviewAuthorizationProperties properties = new InterviewAuthorizationProperties();
        properties.setEnabled(true);
        return properties;
    }

    private record Fixture(InterviewAuthorizationService service, InterviewParticipantService participants) { }
}
