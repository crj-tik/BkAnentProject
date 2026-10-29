package com.bkanent.knowledge.config;

import com.bkanent.knowledge.source.InterviewTranscriptSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class KnowledgeSourceConfiguration {
    @Bean
    public InterviewTranscriptSource interviewTranscriptSource(
            @Qualifier("knowledgeSourceJdbcTemplate") JdbcTemplate sourceJdbcTemplate,
            KnowledgeSourceProperties properties) {
        return new InterviewTranscriptSource(sourceJdbcTemplate, properties);
    }
}
