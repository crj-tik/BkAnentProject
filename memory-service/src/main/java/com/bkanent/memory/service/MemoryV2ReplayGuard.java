package com.bkanent.memory.service;

import com.bkanent.common.memory.MemoryErrorCode;
import com.bkanent.common.memory.MemoryException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

/** Persistent nonce uniqueness makes a signed request single-use across memory instances. */
@Service
public class MemoryV2ReplayGuard {
    private final JdbcTemplate jdbcTemplate;

    public MemoryV2ReplayGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void consume(String keyId, String nonce, long expiresAtEpochSecond) {
        try {
            jdbcTemplate.update("INSERT INTO memory_v2_request_nonce(key_id, nonce, expires_at) VALUES (?, ?, ?)",
                    keyId, nonce, Timestamp.from(Instant.ofEpochSecond(expiresAtEpochSecond)));
        } catch (DuplicateKeyException exception) {
            throw new MemoryException(MemoryErrorCode.REQUEST_REPLAYED);
        } catch (DataAccessException exception) {
            throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
        }
    }

    @Scheduled(fixedDelayString = "${memory.v2.replay-cleanup-ms:60000}")
    public void purgeExpired() {
        try {
            // Keep a grace window so small DB/application clock skew cannot reopen an expired nonce.
            jdbcTemplate.update("DELETE FROM memory_v2_request_nonce WHERE expires_at <= TIMESTAMPADD(MINUTE, -5, CURRENT_TIMESTAMP(6))");
        } catch (DataAccessException exception) {
            throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
        }
    }
}
