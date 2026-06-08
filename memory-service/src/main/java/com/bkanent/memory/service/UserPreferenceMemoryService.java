package com.bkanent.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.common.agent.UserPreferenceRecord;
import com.bkanent.memory.entity.UserPreferenceMemoryEntity;
import com.bkanent.memory.mapper.UserPreferenceMemoryMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class UserPreferenceMemoryService {

    private final UserPreferenceMemoryMapper mapper;

    public UserPreferenceMemoryService(UserPreferenceMemoryMapper mapper) {
        this.mapper = mapper;
    }

    public void upsert(UserPreferenceRecord record) {
        UserPreferenceMemoryEntity entity = mapper.selectOne(
                new LambdaQueryWrapper<UserPreferenceMemoryEntity>()
                        .eq(UserPreferenceMemoryEntity::getUserId, record.userId())
                        .eq(UserPreferenceMemoryEntity::getPreferenceKey, record.preferenceKey())
                        .last("limit 1")
        );
        if (entity == null) {
            entity = new UserPreferenceMemoryEntity();
            entity.setUserId(record.userId());
            entity.setPreferenceKey(record.preferenceKey());
        }
        entity.setPreferenceValue(record.preferenceValue());
        entity.setCategory(record.category());
        entity.setConfidence(record.confidence());
        entity.setEvidence(record.evidence());
        entity.setSourceSessionId(record.sourceSessionId());
        entity.setLastObservedAt(record.lastObservedAt());
        entity.setObservationCount(record.observationCount());
        if (entity.getId() == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
    }

    public List<UserPreferenceRecord> findByUserId(String userId, String category) {
        return mapper.selectList(
                        new LambdaQueryWrapper<UserPreferenceMemoryEntity>()
                                .eq(UserPreferenceMemoryEntity::getUserId, userId)
                                .eq(category != null && !category.isBlank(), UserPreferenceMemoryEntity::getCategory, category)
                                .ge(UserPreferenceMemoryEntity::getConfidence, new BigDecimal("0.1"))
                                .orderByDesc(UserPreferenceMemoryEntity::getConfidence)
                ).stream()
                .map(this::toRecord)
                .toList();
    }

    public void decayConfidence(String userId, String excludePreferenceKey) {
        List<UserPreferenceMemoryEntity> entities = mapper.selectList(
                new LambdaQueryWrapper<UserPreferenceMemoryEntity>()
                        .eq(UserPreferenceMemoryEntity::getUserId, userId)
                        .ne(excludePreferenceKey != null, UserPreferenceMemoryEntity::getPreferenceKey, excludePreferenceKey)
        );
        for (UserPreferenceMemoryEntity entity : entities) {
            BigDecimal newConfidence = entity.getConfidence().subtract(new BigDecimal("0.05"));
            if (newConfidence.compareTo(BigDecimal.ZERO) <= 0) {
                mapper.deleteById(entity.getId());
            } else {
                entity.setConfidence(newConfidence);
                mapper.updateById(entity);
            }
        }
    }

    private UserPreferenceRecord toRecord(UserPreferenceMemoryEntity entity) {
        return new UserPreferenceRecord(
                entity.getUserId(),
                entity.getPreferenceKey(),
                entity.getPreferenceValue(),
                entity.getCategory(),
                entity.getConfidence(),
                entity.getEvidence(),
                entity.getSourceSessionId(),
                entity.getLastObservedAt(),
                entity.getObservationCount()
        );
    }
}
