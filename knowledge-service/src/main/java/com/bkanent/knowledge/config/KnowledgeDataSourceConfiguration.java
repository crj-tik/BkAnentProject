package com.bkanent.knowledge.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class KnowledgeDataSourceConfiguration {
    @Bean(name = "knowledgePrimaryDataSource")
    @Primary
    public DataSource knowledgePrimaryDataSource(KnowledgeDataSourceProperties properties) {
        return build(properties.getPrimary());
    }

    @Bean(name = "knowledgeSourceDataSource")
    public DataSource knowledgeSourceDataSource(KnowledgeDataSourceProperties properties) {
        return build(properties.getSource());
    }

    @Bean(name = "knowledgeJdbcTemplate")
    @Primary
    public JdbcTemplate knowledgeJdbcTemplate(@Qualifier("knowledgePrimaryDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "knowledgeSourceJdbcTemplate")
    public JdbcTemplate knowledgeSourceJdbcTemplate(@Qualifier("knowledgeSourceDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    private static DataSource build(KnowledgeDataSourceProperties.Database properties) {
        if (properties.getUrl() == null || properties.getUrl().isBlank()
                || properties.getUsername() == null || properties.getUsername().isBlank()
                || properties.getPassword() == null || properties.getPassword().isBlank()) {
            throw new IllegalStateException("Knowledge database credentials must be configured");
        }
        return DataSourceBuilder.create()
                .driverClassName(properties.getDriverClassName())
                .url(properties.getUrl())
                .username(properties.getUsername())
                .password(properties.getPassword())
                .build();
    }
}
