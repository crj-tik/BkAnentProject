package com.bkanent.interview.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bkanent.interview.engine.Sanitizer;
import com.bkanent.interview.entity.InterviewAssetEntity;
import com.bkanent.interview.entity.InterviewSessionEntity;
import com.bkanent.interview.entity.InterviewTurnEntity;
import com.bkanent.interview.mapper.InterviewAssetMapper;
import com.bkanent.interview.mapper.InterviewSessionMapper;
import com.bkanent.interview.mapper.InterviewTurnMapper;
import com.bkanent.interview.runtime.InterviewSessionStateMachine;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InterviewCollectionServiceTest {
    @BeforeAll
    static void initializeLambdaMetadata() {
        Configuration configuration = new Configuration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "collection-test");
        TableInfoHelper.initTableInfo(assistant, InterviewAssetEntity.class);
        TableInfoHelper.initTableInfo(assistant, InterviewSessionEntity.class);
        TableInfoHelper.initTableInfo(assistant, InterviewTurnEntity.class);
    }

    @Test
    void closingSessionCollectsSanitizedTurnsAndArchivesAfterAssetCreation() {
        InterviewSessionMapper sessions = mock(InterviewSessionMapper.class);
        InterviewTurnMapper turns = mock(InterviewTurnMapper.class);
        InterviewAssetMapper assets = mock(InterviewAssetMapper.class);
        InterviewSessionStateMachine stateMachine = mock(InterviewSessionStateMachine.class);
        InterviewCollectionService service = new InterviewCollectionService(sessions, turns, assets, stateMachine);

        InterviewSessionEntity closing = session(12L, 6L, InterviewSessionStateMachine.CLOSING_LOCKED);
        InterviewSessionEntity pending = session(12L, 6L, InterviewSessionStateMachine.COLLECT_PENDING);
        when(sessions.selectOne(any())).thenReturn(closing);
        when(stateMachine.transition(12L, InterviewSessionStateMachine.COLLECT_PENDING,
                InterviewSessionStateMachine.Actor.COMPENSATOR)).thenReturn(pending);
        when(assets.selectOne(any())).thenReturn(null);
        when(turns.selectList(any())).thenReturn(List.of(
                turn("RESPONDENT", "ticket=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signaturevalue123456789"),
                turn("INTERVIEWER", "问题已回答")));
        when(assets.insert(any(InterviewAssetEntity.class))).thenAnswer(invocation -> {
            ((InterviewAssetEntity) invocation.getArgument(0)).setId(31L);
            return 1;
        });

        InterviewAssetEntity result = service.collect(closing);

        ArgumentCaptor<InterviewAssetEntity> inserted = ArgumentCaptor.forClass(InterviewAssetEntity.class);
        verify(assets).insert(inserted.capture());
        InterviewAssetEntity saved = inserted.getValue();
        assertThat(saved.getId()).isEqualTo(31L);
        assertThat(saved.getSessionId()).isEqualTo(12L);
        assertThat(saved.getCaseId()).isEqualTo(6L);
        assertThat(saved.getVisibility()).isEqualTo(InterviewAssetService.PRIVATE);
        assertThat(saved.getRawContent()).isNull();
        assertThat(saved.getSanitizationRevision()).isEqualTo(Sanitizer.VERSION);
        assertThat(saved.getContent()).contains("[凭据已移除]").doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        assertThat(saved.getContentHash()).isEqualTo(InterviewAssetService.sha256(saved.getContent()));
        assertThat(result).isSameAs(saved);

        InOrder order = inOrder(stateMachine, assets);
        order.verify(stateMachine).transition(12L, InterviewSessionStateMachine.COLLECT_PENDING,
                InterviewSessionStateMachine.Actor.COMPENSATOR);
        order.verify(assets).insert(saved);
        order.verify(stateMachine).transition(12L, InterviewSessionStateMachine.ARCHIVED,
                InterviewSessionStateMachine.Actor.COMPENSATOR);
    }

    @Test
    void pendingCollectionReusesExistingAutoAssetAndFinishesArchiveWithoutRecreatingIt() {
        InterviewSessionMapper sessions = mock(InterviewSessionMapper.class);
        InterviewTurnMapper turns = mock(InterviewTurnMapper.class);
        InterviewAssetMapper assets = mock(InterviewAssetMapper.class);
        InterviewSessionStateMachine stateMachine = mock(InterviewSessionStateMachine.class);
        InterviewCollectionService service = new InterviewCollectionService(sessions, turns, assets, stateMachine);

        InterviewSessionEntity pending = session(12L, 6L, InterviewSessionStateMachine.COLLECT_PENDING);
        InterviewAssetEntity existing = new InterviewAssetEntity();
        existing.setId(31L);
        existing.setSessionId(12L);
        existing.setSource("AUTO");
        when(sessions.selectOne(any())).thenReturn(pending);
        when(assets.selectOne(any())).thenReturn(existing);

        assertThat(service.collect(pending)).isSameAs(existing);

        verify(assets, never()).insert(any(InterviewAssetEntity.class));
        verifyNoInteractions(turns);
        verify(stateMachine).transition(12L, InterviewSessionStateMachine.ARCHIVED,
                InterviewSessionStateMachine.Actor.COMPENSATOR);
    }

    @Test
    void archivedSessionReturnsItsExistingAssetWithoutReplayingCollection() {
        InterviewSessionMapper sessions = mock(InterviewSessionMapper.class);
        InterviewTurnMapper turns = mock(InterviewTurnMapper.class);
        InterviewAssetMapper assets = mock(InterviewAssetMapper.class);
        InterviewSessionStateMachine stateMachine = mock(InterviewSessionStateMachine.class);
        InterviewCollectionService service = new InterviewCollectionService(sessions, turns, assets, stateMachine);

        InterviewSessionEntity archived = session(12L, 6L, InterviewSessionStateMachine.ARCHIVED);
        InterviewAssetEntity existing = new InterviewAssetEntity();
        existing.setId(31L);
        existing.setSource("AUTO");
        when(sessions.selectOne(any())).thenReturn(archived);
        when(assets.selectOne(any())).thenReturn(existing);

        assertThat(service.collect(archived)).isSameAs(existing);

        verify(assets, never()).insert(any(InterviewAssetEntity.class));
        verifyNoInteractions(turns, stateMachine);
    }

    private static InterviewSessionEntity session(Long id, Long caseId, String status) {
        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setId(id);
        session.setCaseId(caseId);
        session.setStatus(status);
        session.setCreatorWorkNo("A001");
        session.setReferenceMinutes(20);
        return session;
    }

    private static InterviewTurnEntity turn(String role, String sanitizedContent) {
        InterviewTurnEntity turn = new InterviewTurnEntity();
        turn.setRole(role);
        turn.setSanitizedContent(sanitizedContent);
        return turn;
    }
}
