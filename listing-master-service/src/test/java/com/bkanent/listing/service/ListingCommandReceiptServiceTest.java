package com.bkanent.listing.service;

import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 幂等回执：复合主键查插、并发冲突判定与请求摘要确定性。 */
class ListingCommandReceiptServiceTest {

    private JdbcTemplate jdbc;
    private ListingCommandReceiptService receipts;

    @BeforeEach
    void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE listing_command_receipt ("
                + "actor_user_id BIGINT NOT NULL, "
                + "operation VARCHAR(64) NOT NULL, "
                + "request_id VARCHAR(128) NOT NULL, "
                + "request_hash BINARY(32) NOT NULL, "
                + "listing_id BIGINT NULL, "
                + "status VARCHAR(16) NOT NULL, "
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (actor_user_id, operation, request_id))");
        receipts = new ListingCommandReceiptService(jdbc, new ObjectMapper());
    }

    @Test
    void findReturnsEmptyThenReceiptAfterInsert() {
        assertTrue(receipts.find(7L, "listing.create", "register-1").isEmpty());
        byte[] hash = receipts.requestHash(Map.of("title", "t", "totalPrice", new BigDecimal("320.00")));
        receipts.insertSameTransaction(7L, "listing.create", "register-1", hash, 11L, "COMPLETED");
        var found = receipts.find(7L, "listing.create", "register-1");
        assertTrue(found.isPresent());
        assertArrayEquals(hash, found.get().requestHash());
        assertEquals(11L, found.get().listingId());
        assertEquals("COMPLETED", found.get().status());
        assertTrue(receipts.find(8L, "listing.create", "register-1").isEmpty());
        assertTrue(receipts.find(7L, "listing.update.own", "register-1").isEmpty());
    }

    @Test
    void duplicateInsertThrowsDuplicateKeyForConcurrencyDetection() {
        byte[] hash = receipts.requestHash(Map.of("a", 1));
        receipts.insertSameTransaction(7L, "listing.create", "register-1", hash, 11L, "COMPLETED");
        byte[] other = receipts.requestHash(Map.of("a", 2));
        assertThrows(DuplicateKeyException.class,
                () -> receipts.insertSameTransaction(7L, "listing.create", "register-1", other, 12L, "COMPLETED"));
        assertEquals(11L, receipts.find(7L, "listing.create", "register-1").orElseThrow().listingId());
    }

    @Test
    void requestHashIsDeterministicAndContentSensitive() {
        List<String> first = List.of("a", "b");
        List<String> same = List.of("a", "b");
        List<String> different = List.of("b", "a");
        assertArrayEquals(receipts.requestHash(first), receipts.requestHash(same));
        assertFalse(java.util.Arrays.equals(receipts.requestHash(first), receipts.requestHash(different)));
        assertEquals(32, receipts.requestHash(first).length);
    }

    @Test
    void invalidReceiptArgumentsAreRejected() {
        byte[] hash = new byte[32];
        assertThrows(RagException.class,
                () -> receipts.insertSameTransaction(7L, " ", "register-1", hash, 11L, "COMPLETED"));
        assertThrows(RagException.class,
                () -> receipts.insertSameTransaction(7L, "listing.create", null, hash, 11L, "COMPLETED"));
        assertThrows(RagException.class,
                () -> receipts.insertSameTransaction(7L, "listing.create", "register-1", new byte[8], 11L, "COMPLETED"));
        assertEquals(RagErrorCode.INVALID_ARGUMENT,
                assertThrows(RagException.class, () -> receipts.requestHash(null)).code());
    }
}
