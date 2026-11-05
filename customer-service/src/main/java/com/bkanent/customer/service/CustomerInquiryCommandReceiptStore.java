package com.bkanent.customer.service;

import com.bkanent.customer.model.CustomerInquiryCreateRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 买方咨询命令幂等回执存储（bk_customer.customer_inquiry_command_receipt）。
 *
 * <p>复合主键 (actor_user_id, operation, request_id) 不适用 MyBatis Plus，
 * 参照 listing 侧 ListingRagOutbox 使用 JdbcTemplate；业务插入与回执在同一事务，
 * 唯一键串行化并发重试，异常回滚不留下成功回执。请求摘要为规范 JSON 的 SHA-256。
 */
@Service
public class CustomerInquiryCommandReceiptStore {

    /**
     * 咨询创建幂等操作码。
     */
    public static final String CREATE_OPERATION = "customer.inquiry.create";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CustomerInquiryCommandReceiptStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询既有回执；不存在返回空。
     */
    public Optional<Receipt> find(long actorUserId, String operation, String requestId) {
        List<Receipt> rows = jdbc.query(
                "SELECT actor_user_id, operation, request_id, request_hash, inquiry_id, status, created_at "
                        + "FROM customer_inquiry_command_receipt "
                        + "WHERE actor_user_id=? AND operation=? AND request_id=?",
                (rs, rowNumber) -> new Receipt(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getBytes(4), (Long) rs.getObject(5), rs.getString(6),
                        rs.getTimestamp(7).toLocalDateTime()),
                actorUserId, operation, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 插入回执；并发重复键抛出异常由事务回滚串行化。
     */
    public void insert(long actorUserId, String operation, String requestId, byte[] requestHash,
                       Long inquiryId, String status) {
        jdbc.update("INSERT INTO customer_inquiry_command_receipt "
                        + "(actor_user_id, operation, request_id, request_hash, inquiry_id, status) "
                        + "VALUES (?,?,?,?,?,?)",
                actorUserId, operation, requestId, requestHash, inquiryId, status);
    }

    /**
     * 计算请求规范摘要：键排序后的 JSON 做 SHA-256，同一逻辑请求重试得到同一摘要。
     */
    public byte[] requestHash(Long customerId, CustomerInquiryCreateRequest request) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("customerId", customerId);
        canonical.put("listingId", request.listingId());
        canonical.put("inquiryChannel", request.inquiryChannel());
        canonical.put("inquiryContent", request.inquiryContent());
        try {
            return MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("咨询请求摘要计算失败", failure);
        }
    }

    /**
     * 回执记录。
     */
    public record Receipt(long actorUserId, String operation, String requestId, byte[] requestHash,
                          Long inquiryId, String status, LocalDateTime createdAt) {
    }
}
