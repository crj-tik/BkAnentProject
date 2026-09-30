package com.bkanent.memory.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Populates the non-null audit columns inherited from BaseEntity. */
@Component
public class MemoryMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        if (getFieldValByName("createdAt", metaObject) == null) {
            setFieldValByName("createdAt", now, metaObject);
        }
        if (getFieldValByName("updatedAt", metaObject) == null) {
            setFieldValByName("updatedAt", now, metaObject);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        setFieldValByName("updatedAt", LocalDateTime.now(), metaObject);
    }
}
