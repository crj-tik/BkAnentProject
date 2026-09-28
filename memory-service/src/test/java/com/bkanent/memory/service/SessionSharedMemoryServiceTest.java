package com.bkanent.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.common.agent.SessionMemoryUpsertRequest;
import com.bkanent.memory.entity.SessionSharedMemoryEntity;
import com.bkanent.memory.mapper.SessionSharedMemoryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionSharedMemoryServiceTest {

    @Test
    void doesNotAllowSessionOwnershipToBeReassigned() {
        SessionSharedMemoryMapper mapper = mock(SessionSharedMemoryMapper.class);
        SessionSharedMemoryEntity existing = new SessionSharedMemoryEntity();
        existing.setId(12L);
        existing.setSessionId("session");
        existing.setUserId("7");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        SessionSharedMemoryService service = new SessionSharedMemoryService(mapper, new ObjectMapper());

        assertThatThrownBy(() -> service.upsert(new SessionMemoryUpsertRequest(
                "session", "42", Map.of(), null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ownership cannot be changed");

        verify(mapper, never()).updateById(any(SessionSharedMemoryEntity.class));
    }
}
