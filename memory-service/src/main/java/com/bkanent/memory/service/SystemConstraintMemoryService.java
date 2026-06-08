package com.bkanent.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.common.agent.SystemConstraintRecord;
import com.bkanent.memory.entity.SystemConstraintMemoryEntity;
import com.bkanent.memory.mapper.SystemConstraintMemoryMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SystemConstraintMemoryService {

    private final SystemConstraintMemoryMapper mapper;

    public SystemConstraintMemoryService(SystemConstraintMemoryMapper mapper) {
        this.mapper = mapper;
    }

    public void upsert(SystemConstraintRecord record) {
        SystemConstraintMemoryEntity entity = mapper.selectOne(
                new LambdaQueryWrapper<SystemConstraintMemoryEntity>()
                        .eq(SystemConstraintMemoryEntity::getConstraintKey, record.constraintKey())
                        .last("limit 1")
        );
        if (entity == null) {
            entity = new SystemConstraintMemoryEntity();
            entity.setConstraintKey(record.constraintKey());
        }
        entity.setConstraintText(record.constraintText());
        entity.setCategory(record.category());
        entity.setSource(record.source() != null ? record.source() : "manual");
        entity.setTags(record.tags());
        entity.setActive(record.active() != null ? record.active() : true);
        if (entity.getId() == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
    }

    public List<SystemConstraintRecord> findByCategory(String category) {
        return mapper.selectList(
                        new LambdaQueryWrapper<SystemConstraintMemoryEntity>()
                                .eq(category != null && !category.isBlank(), SystemConstraintMemoryEntity::getCategory, category)
                                .eq(SystemConstraintMemoryEntity::getActive, true)
                ).stream()
                .map(this::toRecord)
                .toList();
    }

    public List<SystemConstraintRecord> searchByTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return findActive();
        }
        return mapper.selectList(
                        new LambdaQueryWrapper<SystemConstraintMemoryEntity>()
                                .eq(SystemConstraintMemoryEntity::getActive, true)
                ).stream()
                .filter(e -> e.getTags() != null && containsAnyTag(e.getTags(), tags))
                .map(this::toRecord)
                .toList();
    }

    public List<SystemConstraintRecord> findActive() {
        return mapper.selectList(
                        new LambdaQueryWrapper<SystemConstraintMemoryEntity>()
                                .eq(SystemConstraintMemoryEntity::getActive, true)
                ).stream()
                .map(this::toRecord)
                .toList();
    }

    private boolean containsAnyTag(String dbTags, String queryTags) {
        String[] query = queryTags.split(",");
        for (String tag : query) {
            if (dbTags.contains(tag.trim())) {
                return true;
            }
        }
        return false;
    }

    private SystemConstraintRecord toRecord(SystemConstraintMemoryEntity entity) {
        return new SystemConstraintRecord(
                entity.getConstraintKey(),
                entity.getConstraintText(),
                entity.getCategory(),
                entity.getSource(),
                entity.getTags(),
                entity.getActive()
        );
    }
}
