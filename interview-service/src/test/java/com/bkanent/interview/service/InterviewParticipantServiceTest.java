package com.bkanent.interview.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bkanent.interview.entity.InterviewParticipantEntity;
import com.bkanent.interview.mapper.InterviewParticipantMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InterviewParticipantServiceTest {
    @BeforeAll
    static void initializeLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "participant-test"),
                InterviewParticipantEntity.class);
    }

    @Test
    void creatorIsConfirmedAndSelectedRespondentStartsPending() {
        InterviewParticipantMapper mapper = mock(InterviewParticipantMapper.class);
        when(mapper.insert(any(InterviewParticipantEntity.class))).thenReturn(1);
        InterviewParticipantService service = new InterviewParticipantService(mapper);

        service.createSessionRelations(12L, 101L, 202L);

        ArgumentCaptor<InterviewParticipantEntity> rows = ArgumentCaptor.forClass(InterviewParticipantEntity.class);
        verify(mapper, times(2)).insert(rows.capture());
        List<InterviewParticipantEntity> inserted = rows.getAllValues();
        assertEquals(101L, inserted.get(0).getUserId());
        assertEquals(InterviewParticipantService.CREATE, inserted.get(0).getRelation());
        assertEquals(InterviewParticipantService.CONFIRMED, inserted.get(0).getState());
        assertEquals(202L, inserted.get(1).getUserId());
        assertEquals(InterviewParticipantService.RESPOND, inserted.get(1).getRelation());
        assertEquals(InterviewParticipantService.PENDING, inserted.get(1).getState());
        assertNull(inserted.get(1).getConfirmedAt());
    }

    @Test
    void confirmationUpdateIsBoundToTheAuthenticatedRespondentOnly() {
        InterviewParticipantMapper mapper = mock(InterviewParticipantMapper.class);
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);
        when(mapper.selectCount(any())).thenReturn(0L);
        InterviewParticipantService service = new InterviewParticipantService(mapper);

        assertFalse(service.confirmRespondent(12L, 202L));

        ArgumentCaptor<LambdaUpdateWrapper<InterviewParticipantEntity>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), update.capture());
        update.getValue().getSqlSegment();
        assertTrue(update.getValue().getParamNameValuePairs().containsValue(12L));
        assertTrue(update.getValue().getParamNameValuePairs().containsValue(202L));
        assertTrue(update.getValue().getParamNameValuePairs().containsValue(InterviewParticipantService.PENDING));
        assertTrue(update.getValue().getParamNameValuePairs().containsValue(InterviewParticipantService.RESPOND));
    }
}
