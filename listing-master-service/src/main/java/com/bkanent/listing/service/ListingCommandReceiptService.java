package com.bkanent.listing.service;

import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

/**
 * ListingCommandReceiptService 房源命令幂等回执服务（listing_command_receipt）。
 * 复合主键 (actor_user_id, operation, request_id) 不适合 MyBatis Plus 单列主键模型，
 * 因此基于 JdbcTemplate 实现；业务插入与回执同事务，唯一键串行化并发重试，
 * 异常回滚不留下成功回执。参照 {@link com.bkanent.listing.rag.ListingRagOutbox} 的事务约定。
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ListingCommandReceiptService {

    /**
     * 字段：jdbc。
     */
    private final JdbcTemplate jdbc;
    /**
     * 字段：json（确定性 JSON 序列化，record 字段序确定）。
     */
    private final ObjectMapper json;

    /**
     * 构造 ListingCommandReceiptService 实例。
     */
    public ListingCommandReceiptService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * 查询既有回执；无回执返回空。调用方必须处于业务事务内。
     */
    public Optional<Receipt> find(long actorUserId, String operation, String requestId) {
        if (operation == null || operation.isBlank() || requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        List<Receipt> rows = jdbc.query(
                "SELECT request_hash, listing_id, status FROM listing_command_receipt "
                        + "WHERE actor_user_id=? AND operation=? AND request_id=?",
                (rs, n) -> new Receipt(rs.getBytes(1), rs.getObject(2, Long.class), rs.getString(3)),
                actorUserId, operation, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 同事务写入回执。主键冲突（并发重试被唯一键串行化）时抛出 {@link DuplicateKeyException}，
     * 由调用方整体回滚后按既有回执重放，不留下半成品业务数据。
     */
    public void insertSameTransaction(long actorUserId, String operation, String requestId,
                                      byte[] requestHash, Long listingId, String status) {
        if (operation == null || operation.isBlank() || requestId == null || requestId.isBlank()
                || requestHash == null || requestHash.length != 32 || status == null || status.isBlank()) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "commandReceipt");
        }
        try {
            jdbc.update("INSERT INTO listing_command_receipt (actor_user_id, operation, request_id, "
                            + "request_hash, listing_id, status) VALUES (?,?,?,?,?,?)",
                    actorUserId, operation, requestId, requestHash, listingId, status);
        } catch (DuplicateKeyException failure) {
            // 并发重试：唯一键串行化，调用方事务回滚后按既有回执重放同一结果。
            throw failure;
        }
    }

    /**
     * 计算请求摘要：SHA-256(请求的确定性 JSON 序列化)。record 字段序确定，
     * 同内容请求摘要一致，不同内容摘要不同。
     */
    public byte[] requestHash(Object request) {
        if (request == null) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "requestHash");
        }
        try {
            byte[] payload = json.writeValueAsBytes(request);
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "requestHash");
        }
    }

    /**
     * 幂等回执：请求摘要、业务对象 ID 与完成状态。
     */
    public record Receipt(byte[] requestHash, Long listingId, String status) {
    }
}
