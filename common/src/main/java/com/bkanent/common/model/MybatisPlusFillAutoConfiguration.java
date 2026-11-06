package com.bkanent.common.model;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.time.LocalDateTime;

/**
 * BaseEntity 的 createdAt/updatedAt 使用 FieldFill 注解但部分服务未注册填充器，
 * 导致 INSERT 显式携带 NULL 覆盖数据库默认值（真实 MySQL 报 Column 'created_at' cannot be null）。
 * 此自动装配仅为没有服务级填充器的服务提供兜底；已有自定义 MetaObjectHandler 的服务不受影响。
 */
@AutoConfiguration
public class MybatisPlusFillAutoConfiguration {

    /** 插入时填 createdAt/updatedAt，更新时刷新 updatedAt；已有值不覆盖；服务自带 handler 时让位。 */
    @Bean
    @ConditionalOnMissingBean(MetaObjectHandler.class)
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
