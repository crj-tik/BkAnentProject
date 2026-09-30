package com.bkanent.memory.config;

import com.bkanent.memory.entity.SessionSharedMemoryEntity;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryMetaObjectHandlerTest {

    @Test
    void fillsInsertTimesAndRefreshesUpdateTimeWithoutChangingCreationTime() {
        MemoryMetaObjectHandler handler = new MemoryMetaObjectHandler();
        SessionSharedMemoryEntity entity = new SessionSharedMemoryEntity();
        handler.insertFill(SystemMetaObject.forObject(entity));
        assertNotNull(entity.getCreatedAt());
        assertNotNull(entity.getUpdatedAt());

        LocalDateTime originalCreation = entity.getCreatedAt();
        entity.setUpdatedAt(originalCreation.minusDays(1));
        handler.updateFill(SystemMetaObject.forObject(entity));
        assertEquals(originalCreation, entity.getCreatedAt());
        assertTrue(entity.getUpdatedAt().isAfter(originalCreation.minusDays(1)));
    }
}
