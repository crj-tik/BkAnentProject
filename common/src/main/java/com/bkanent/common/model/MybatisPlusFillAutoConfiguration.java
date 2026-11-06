package com.bkanent.common.model;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.LocalDateTime;

/**
 * BaseEntity 的 createdAt/updatedAt 使用 FieldFill 注解但生产服务从未注册填充器，
 * 导致 INSERT 显式携带 NULL 覆盖数据库默认值（真实 MySQL 报 Column 'created_at' cannot be null）。
 * 此自动装配为所有服务统一提供插入/更新时间填充。
 */
@AutoConfiguration
public class MybatisPlusFillAutoConfiguration {

    /** 插入时填 createdAt/updatedAt，更新时刷新 updatedAt；已有值不覆盖。 */
    @Bean
    public MetaObjectHandler baseEntityTimeFillHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
