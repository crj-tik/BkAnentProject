package com.bkanent.interview.config;

import com.bkanent.interview.entity.InterviewCaseEntity;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewMetaObjectHandlerTest {

    @Test
    void fillsInsertTimesAndRefreshesUpdateTimeWithoutChangingCreationTime() {
        InterviewMetaObjectHandler handler = new InterviewMetaObjectHandler();
        InterviewCaseEntity entity = new InterviewCaseEntity();

        handler.insertFill(SystemMetaObject.forObject(entity));

        assertNotNull(entity.getCreatedAt());
        assertNotNull(entity.getUpdatedAt());
        LocalDateTime originalCreation = entity.getCreatedAt();
        entity.setUpdatedAt(originalCreation.minusDays(1));

        handler.updateFill(SystemMetaObject.forObject(entity));

        assertEquals(originalCreation, entity.getCreatedAt());
        assertTrue(entity.getUpdatedAt().isAfter(originalCreation.minusDays(1)));
    }

    @Test
    void preservesExplicitCreationTimeDuringInsertFill() {
        InterviewMetaObjectHandler handler = new InterviewMetaObjectHandler();
        InterviewCaseEntity entity = new InterviewCaseEntity();
        LocalDateTime creationTime = LocalDateTime.of(2025, 1, 2, 3, 4, 5);
        entity.setCreatedAt(creationTime);

        handler.insertFill(SystemMetaObject.forObject(entity));

        assertEquals(creationTime, entity.getCreatedAt());
        assertNotNull(entity.getUpdatedAt());
    }
}
