package com.bkanent.auth;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformOperationsCallerConfigurationTest {
    @Test
    void unifiedPlatformOperationsCallerHasOnlyBeaconResolutionAccess() throws Exception {
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory"));
        for (String filename : new String[]{"nacos/auth-service.yaml", "auth-service/src/main/resources/application.yml"}) {
            String yaml = Files.readString(root.resolve(filename)).replace("\r\n", "\n");
            assertTrue(yaml.contains("platform-operations-service-current:\n        caller-service: platform-operations-service"
                    + "\n        public-key: ${PLATFORM_OPERATIONS_SERVICE_IDENTITY_PUBLIC_KEY:}"
                    + "\n        allowed-operations: [RESOLVE_BEACON]"), filename);
            assertFalse(yaml.contains("compare-engine-service-current:"), filename);
            assertFalse(yaml.contains("business-service-current:"), filename);
            assertFalse(yaml.contains("business-review-service-current:"), filename);
            assertFalse(yaml.contains("knowledge-service-current:"), filename);
            assertFalse(yaml.contains("contract-service-current:"), filename);
            assertFalse(yaml.contains("settlement-service-current:"), filename);
        }
    }

    @Test
    void localH2CatalogMatchesCurrentPlatformOperationsAndExcludesRetiredSettlementPermissions() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:auth_seed_" + UUID.randomUUID().toString().replace("-", "")
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema-h2.sql"),
                new ClassPathResource("db/data-h2.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> expected = List.of(
                "agent.workflow.invoke.platform-operations.analytics",
                "agent.workflow.invoke.platform-operations.review",
                "agent.workflow.invoke.platform-operations.knowledge",
                "business.review.create.store", "business.review.read.store",
                "business.review.plan.write.store", "business.review.plan.confirm.store",
                "business.review.action.feedback.store", "business.review.observation.read.store",
                "knowledge.case.create.own", "knowledge.case.edit.own", "knowledge.case.submit.own",
                "knowledge.case.generate.own", "knowledge.case.review.decide.own",
                "knowledge.case.review.decide.store", "knowledge.case.publish.own",
                "knowledge.case.publish.store", "knowledge.case.withdraw.own",
                "knowledge.case.withdraw.store", "knowledge.case.read.own", "knowledge.case.read.store");
        for (String permission : expected) {
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_permission WHERE permission_code=?",
                    Integer.class, permission), permission);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_role_permission WHERE permission_code=?",
                    Integer.class, permission), "not auto-granted: " + permission);
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_permission WHERE permission_code LIKE 'settlement.%'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_permission WHERE permission_code IN "
                + "('agent.workflow.invoke.business-review','agent.workflow.invoke.knowledge',"
                + "'agent.workflow.invoke.platform-operations')", Integer.class));
    }
}
