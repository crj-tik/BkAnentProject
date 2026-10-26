package com.bkanent.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bkanent.interview.config.InterviewAuthorizationProperties;
import com.bkanent.interview.entity.InterviewParticipantTicketEntity;
import com.bkanent.interview.mapper.InterviewParticipantTicketMapper;
import com.bkanent.interview.security.InterviewAuthorizationScope;
import com.bkanent.interview.security.InterviewAuthzFixture;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InterviewParticipantTicketServiceTest {
    private InterviewParticipantTicketMapper mapper;
    private InterviewAuthorizationService authorization;
    private InterviewParticipantTicketService service;
    private InterviewAuthorizationScope.Scope scope;

    @BeforeAll
    static void initializeLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "ticket-test"),
                InterviewParticipantTicketEntity.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(InterviewParticipantTicketMapper.class);
        authorization = mock(InterviewAuthorizationService.class);
        InterviewAuthorizationProperties properties = new InterviewAuthorizationProperties();
        properties.setEnabled(true);
        service = new InterviewParticipantTicketService(mapper, properties, authorization);
        scope = InterviewAuthorizationScope.open(InterviewAuthzFixture.response("202",
                List.of(InterviewAuthorizationService.READ, InterviewAuthorizationService.RESPOND,
                        InterviewAuthorizationService.INITIATE)));
    }

    @AfterEach
    void clearScope() { scope.close(); }

    @Test
    void issueStoresOnlyHashedValueAndBindsTheAuthenticatedActorAndPurpose() {
        when(mapper.insert(any(InterviewParticipantTicketEntity.class))).thenAnswer(call -> {
            ((InterviewParticipantTicketEntity) call.getArgument(0)).setId(44L);
            return 1;
        });
        LocalDateTime before = LocalDateTime.now().plusMinutes(14);

        InterviewParticipantTicketService.IssuedCredential issued = service.issue(12L,
                InterviewParticipantTicketService.RESPOND);

        ArgumentCaptor<InterviewParticipantTicketEntity> inserted =
                ArgumentCaptor.forClass(InterviewParticipantTicketEntity.class);
        verify(mapper).insert(inserted.capture());
        InterviewParticipantTicketEntity row = inserted.getValue();
        assertEquals(12L, row.getSessionId());
        assertEquals(202L, row.getUserId());
        assertEquals(InterviewParticipantTicketService.RESPOND, row.getPurpose());
        assertEquals(1, row.getRevision());
        assertEquals(43, issued.credential().length());
        assertNotEquals(issued.credential(), row.getTicketHash());
        assertEquals(64, row.getTicketHash().length());
        assertTrue(issued.expiresAt().isAfter(before));
        assertEquals(issued.credentialId(), row.getId());
        verify(authorization).requireRespondent(12L);
    }

    @Test
    void validationScopesTheLookupToSessionActorPurposeAndExpiry() {
        InterviewParticipantTicketEntity row = new InterviewParticipantTicketEntity();
        row.setId(44L);
        row.setSessionId(12L);
        row.setUserId(202L);
        row.setPurpose(InterviewParticipantTicketService.READ);
        row.setTicketHash("a".repeat(64));
        row.setRevision(1);
        row.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(mapper.selectOne(any())).thenReturn(row);

        var lease = service.validate(12L, "A".repeat(43), InterviewParticipantTicketService.READ);

        assertNotNull(lease);
        assertEquals(202L, lease.userId());
        assertEquals(12L, lease.sessionId());
        assertEquals(InterviewParticipantTicketService.READ, lease.purpose());
        ArgumentCaptor<LambdaQueryWrapper<InterviewParticipantTicketEntity>> query =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectOne(query.capture());
        query.getValue().getSqlSegment();
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(12L));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(202L));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(InterviewParticipantTicketService.READ));
    }

    @Test
    void revokeChangesOnlyTheNamedCredentialAndItsRevision() {
        InterviewParticipantTicketEntity row = new InterviewParticipantTicketEntity();
        row.setId(44L);
        row.setSessionId(12L);
        row.setUserId(202L);
        row.setRevision(3);
        when(mapper.selectOne(any())).thenReturn(row);
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        assertTrue(service.revoke(12L, 44L));

        ArgumentCaptor<LambdaUpdateWrapper<InterviewParticipantTicketEntity>> update =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), update.capture());
        update.getValue().getSqlSegment();
        assertTrue(update.getValue().getParamNameValuePairs().containsValue(44L));
        assertTrue(update.getValue().getParamNameValuePairs().containsValue(12L));
        assertTrue(update.getValue().getParamNameValuePairs().containsValue(3));
        verifyNoInteractions(authorization);
    }

    @Test
    void revokingAnotherUsersCredentialRequiresInitiatorAuthorization() {
        InterviewParticipantTicketEntity row = new InterviewParticipantTicketEntity();
        row.setId(44L);
        row.setSessionId(12L);
        row.setUserId(303L);
        row.setRevision(1);
        when(mapper.selectOne(any())).thenReturn(row);
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        assertTrue(service.revoke(12L, 44L));

        verify(authorization).requireInitiator(12L, InterviewAuthorizationService.INITIATE);
    }
}
