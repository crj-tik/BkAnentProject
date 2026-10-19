package com.bkanent.memory.service;

import com.bkanent.common.memory.MemoryErrorCode;
import com.bkanent.common.memory.MemoryException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemoryV2ReplayGuardTest {
    private JdbcTemplate database() {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:replay;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE IF NOT EXISTS memory_v2_request_nonce (key_id VARCHAR(64) NOT NULL, nonce VARCHAR(64) NOT NULL, expires_at TIMESTAMP(6) NOT NULL, created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6), PRIMARY KEY(key_id, nonce))");
        return jdbc;
    }

    @Test
    void nonceIsSingleUseAndScopedBySigningKey() {
        MemoryV2ReplayGuard guard = new MemoryV2ReplayGuard(database());

        long expires = java.time.Instant.now().plusSeconds(45).getEpochSecond();
        guard.consume("caller-k1", "nonce-abcdefghijklmnop", expires);
        MemoryException replay = assertThrows(MemoryException.class,
                () -> guard.consume("caller-k1", "nonce-abcdefghijklmnop", expires));
        assertEquals(MemoryErrorCode.REQUEST_REPLAYED, replay.code());

        guard.consume("caller-k2", "nonce-abcdefghijklmnop", expires);
    }

    @Test
    void databaseFailureFailsClosed() {
        MemoryV2ReplayGuard guard = new MemoryV2ReplayGuard(new JdbcTemplate(
                new DriverManagerDataSource("jdbc:h2:mem:missing-table", "sa", "")));

        MemoryException unavailable = assertThrows(MemoryException.class,
                () -> guard.consume("caller-k1", "nonce-abcdefghijklmnop", java.time.Instant.now().plusSeconds(45).getEpochSecond()));
        assertEquals(MemoryErrorCode.MEMORY_UNAVAILABLE, unavailable.code());
    }

    @Test
    void cleanupRetainsTheClockSkewGraceWindow() {
        JdbcTemplate jdbc = database();
        jdbc.update("INSERT INTO memory_v2_request_nonce(key_id, nonce, expires_at) VALUES (?, ?, ?)",
                "caller-k1", "nonce-expired-abcdefghijkl", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(360)));
        jdbc.update("INSERT INTO memory_v2_request_nonce(key_id, nonce, expires_at) VALUES (?, ?, ?)",
                "caller-k1", "nonce-recent-abcdefghijkl", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(120)));

        new MemoryV2ReplayGuard(jdbc).purgeExpired();

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM memory_v2_request_nonce", Integer.class));
        assertEquals("nonce-recent-abcdefghijkl", jdbc.queryForObject(
                "SELECT nonce FROM memory_v2_request_nonce", String.class));
    }
}
