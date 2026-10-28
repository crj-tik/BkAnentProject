package com.bkanent.marketing.campaign;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CampaignSchemaMigrationTest {

    @Test
    void migrationIsRepeatableAndLegacyIdsAreNamespacedByEntity() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:marketing_campaign_schema;MODE=MySQL;DB_CLOSE_DELAY=-1");
        Path migration = repoPath("sql/migrations/20261026_marketing_campaign_lifecycle.sql");
        Path check = repoPath("docker/mysql/checks/marketing-campaign-lifecycle.sql");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new FileSystemResource(migration));
        populator.execute(dataSource);
        populator.execute(dataSource);

        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery(Files.readString(check))) {
            result.next();
            assertEquals(1, result.getInt(1));
        }

        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema='PUBLIC' AND table_name LIKE 'MARKETING_CAMPAIGN_%'")) {
                result.next();
                assertEquals(13, result.getInt(1));
            }
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema='PUBLIC' AND table_name LIKE '%STUDIO%'")) {
                result.next();
                assertEquals(0, result.getInt(1));
            }
        }

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO marketing_campaign_legacy_map " +
                    "(source_system,entity_type,legacy_id,target_table,target_id) VALUES " +
                    "('promotion-service','PUBLISH_RECORD','1','marketing_campaign_publish_attempt','a')");
            statement.executeUpdate("INSERT INTO marketing_campaign_legacy_map " +
                    "(source_system,entity_type,legacy_id,target_table,target_id) VALUES " +
                    "('promotion-service','BRAND_ASSET','1','marketing_campaign_brand_asset','a')");
            assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO marketing_campaign_legacy_map " +
                    "(source_system,entity_type,legacy_id,target_table,target_id) VALUES " +
                    "('promotion-service','PUBLISH_RECORD','1','marketing_campaign_publish_attempt','b')"));
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM marketing_campaign_legacy_map")) {
                result.next();
                assertEquals(2, result.getInt(1));
            }
        }
    }

    private Path repoPath(String relative) {
        Path direct = Path.of(relative);
        if (Files.exists(direct)) return direct;
        Path parent = Path.of("..", relative);
        if (Files.exists(parent)) return parent;
        throw new IllegalStateException("Cannot locate repository file: " + relative);
    }
}
