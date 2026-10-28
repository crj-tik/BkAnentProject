package com.bkanent.knowledge.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRepositoryTest {
    @Test
    void aLaterRejectionInvalidatesAnEarlierApprovalForTheSameRevision() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:knowledge-review-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE knowledge_review_decision (
                    decision_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    knowledge_id VARCHAR(64) NOT NULL,
                    revision_no INT NOT NULL,
                    decision VARCHAR(16) NOT NULL
                )
                """);
        jdbc.update("INSERT INTO knowledge_review_decision(knowledge_id,revision_no,decision) VALUES (?,?,?)",
                "knowledge-1", 1, "APPROVE");
        jdbc.update("INSERT INTO knowledge_review_decision(knowledge_id,revision_no,decision) VALUES (?,?,?)",
                "knowledge-1", 1, "REJECT");

        KnowledgeRepository repository = new KnowledgeRepository(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());

        assertThat(repository.hasApproval("knowledge-1", 1)).isFalse();
    }
}
