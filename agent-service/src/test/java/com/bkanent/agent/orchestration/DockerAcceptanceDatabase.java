package com.bkanent.agent.orchestration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.bkanent.agent.mapper.*;
import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.LocalDateTime;

/** Actual acceptance DB wiring, deliberately independent of business service boot profiles. */
final class DockerAcceptanceDatabase {
    final JdbcTemplate jdbc;
    final SqlSessionTemplate session;
    DockerAcceptanceDatabase() throws Exception {
        var datasource = new DriverManagerDataSource(System.getenv("BK_AGENT_DB_URL"), System.getenv("BK_AGENT_DB_USER"), System.getenv("BK_AGENT_DB_PASSWORD"));
        jdbc = new JdbcTemplate(datasource);
        var global = new GlobalConfig();
        global.setMetaObjectHandler(new MetaObjectHandler() {
            public void insertFill(MetaObject object) {
                strictInsertFill(object, "createdAt", LocalDateTime.class, LocalDateTime.now());
                strictInsertFill(object, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
            public void updateFill(MetaObject object) { strictUpdateFill(object, "updatedAt", LocalDateTime.class, LocalDateTime.now()); }
        });
        var bean = new MybatisSqlSessionFactoryBean(); bean.setDataSource(datasource);
        bean.setConfiguration(new MybatisConfiguration()); bean.setGlobalConfig(global);
        var factory = bean.getObject();
        for (Class<?> type : new Class<?>[]{AgentWorkflowCheckpointMapper.class, AgentWorkflowApprovalClaimMapper.class,
                AgentTaskArtifactMapper.class, AgentEventAuditMapper.class}) factory.getConfiguration().addMapper(type);
        session = new SqlSessionTemplate(factory);
    }
}
