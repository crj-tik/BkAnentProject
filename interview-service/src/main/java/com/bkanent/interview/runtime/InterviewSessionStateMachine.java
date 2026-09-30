package com.bkanent.interview.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.interview.config.InterviewRuntimeProperties;
import com.bkanent.interview.entity.InterviewSessionEntity;
import com.bkanent.interview.mapper.InterviewSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 会话状态机：状态推进唯一入口 + 推进权限表 + 乐观锁。
 *
 * <p>治理面（开台/导演指令）、运行面（话轮推进）、补偿器（归集）三方
 * 写同一会话——状态列只能经本类变更，任何入口不得直接 UPDATE 状态。</p>
 */
@Component
public class InterviewSessionStateMachine {

    private static final Logger log = LoggerFactory.getLogger(InterviewSessionStateMachine.class);

    /** 会话状态。 */
    public static final String DRAFT = "DRAFT";
    public static final String QUESTIONS_CONFIRMED = "QUESTIONS_CONFIRMED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String CLOSING_LOCKED = "CLOSING_LOCKED";
    public static final String COLLECT_PENDING = "COLLECT_PENDING";
    public static final String ARCHIVED = "ARCHIVED";

    /**
     * 推进权限表：目标状态 → 允许的推进方（对齐 design D3：
     * →QUESTIONS_CONFIRMED/→IN_PROGRESS 仅治理面；→CLOSING_LOCKED 仅运行面
     * （含导演指令触发的运行面收束）；→COLLECT_PENDING/→ARCHIVED 仅补偿器）。
     */
    public enum Actor { GOVERNANCE, RUNTIME, COMPENSATOR }

    private static final java.util.Map<String, Set<String>> TRANSITIONS = java.util.Map.of(
            QUESTIONS_CONFIRMED, Set.of("GOVERNANCE"),
            IN_PROGRESS, Set.of("GOVERNANCE"),
            CLOSING_LOCKED, Set.of("RUNTIME"),
            COLLECT_PENDING, Set.of("COMPENSATOR"),
            ARCHIVED, Set.of("COMPENSATOR")
    );

    /** 状态推进非法异常。 */
    public static class IllegalTransitionException extends RuntimeException {
        public IllegalTransitionException(String message) {
            super(message);
        }
    }

    private final InterviewSessionMapper sessionMapper;
    private final InterviewRuntimeProperties properties;

    public InterviewSessionStateMachine(InterviewSessionMapper sessionMapper,
                                        InterviewRuntimeProperties properties) {
        this.sessionMapper = sessionMapper;
        this.properties = properties;
    }

    /**
     * 推进会话状态（带乐观锁与权限校验）。
     *
     * @param sessionId 会话 ID
     * @param target    目标状态（常量）
     * @param actor     推进方
     * @return 推进后的实体
     */
    public InterviewSessionEntity transition(Long sessionId, String target, Actor actor) {
        InterviewSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new IllegalTransitionException("session not found: " + sessionId);
        }
        String current = session.getStatus();
        if (current.equals(target)) {
            return session;
        }
        if (!isAllowed(target, actor)) {
            throw new IllegalTransitionException(
                    "actor " + actor + " not allowed to transition session to " + target);
        }
        String expectedPrev = expectedPrevious(target);
        if (expectedPrev != null && !expectedPrev.equals(current)) {
            throw new IllegalTransitionException(
                    "session " + sessionId + " in " + current + " cannot jump to " + target);
        }

        InterviewSessionEntity update = new InterviewSessionEntity();
        update.setId(sessionId);
        update.setStatus(target);
        update.setVersion(session.getVersion() + 1);
        if (IN_PROGRESS.equals(target)) {
            update.setStartedAt(LocalDateTime.now());
        }
        if (CLOSING_LOCKED.equals(target)) {
            update.setClosingLocked(1);
            update.setClosedAt(LocalDateTime.now());
        }
        int rows = sessionMapper.update(update,
                new LambdaQueryWrapper<InterviewSessionEntity>()
                        .eq(InterviewSessionEntity::getId, sessionId)
                        .eq(InterviewSessionEntity::getVersion, session.getVersion()));
        if (rows == 0) {
            throw new IllegalTransitionException(
                    "optimistic lock conflict on session " + sessionId + " -> " + target);
        }
        log.info("Session {} transitioned {} -> {} by {}", sessionId, current, target, actor);
        return sessionMapper.selectById(sessionId);
    }

    /** 目标状态的前置状态（null = 无固定前置）。 */
    static String expectedPrevious(String target) {
        return switch (target) {
            case QUESTIONS_CONFIRMED -> DRAFT;
            case IN_PROGRESS -> QUESTIONS_CONFIRMED;
            case CLOSING_LOCKED -> IN_PROGRESS;
            case COLLECT_PENDING -> CLOSING_LOCKED;
            case ARCHIVED -> COLLECT_PENDING;
            default -> null;
        };
    }

    /** 权限判定（包级可见以便单测防回归）。 */
    static boolean isAllowed(String target, Actor actor) {
        return TRANSITIONS.getOrDefault(target, Set.of()).contains(actor.name());
    }

    // ---------- 会话凭据（功能寻址所需的最小凭据，非鉴权体系） ----------

    /**
     * 签发会话凭据：HMAC(sessionId:expiresAt)，落库存哈希。
     */
    public String issueTicket(InterviewSessionEntity session) {
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(properties.getTicket().getTtlHours());
        String payload = session.getId() + ":" + expiresAt;
        String ticket = hmac(payload);
        InterviewSessionEntity update = new InterviewSessionEntity();
        update.setId(session.getId());
        update.setTicketHash(sha256(ticket));
        update.setTicketExpiresAt(expiresAt);
        sessionMapper.updateById(update);
        return ticket;
    }

    /**
     * 校验会话凭据：只放行本场会话。
     */
    public boolean validateTicket(Long sessionId, String ticket) {
        if (sessionId == null || ticket == null || ticket.isBlank()) {
            return false;
        }
        InterviewSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null || session.getTicketHash() == null || session.getTicketExpiresAt() == null) {
            return false;
        }
        if (session.getTicketExpiresAt().isBefore(LocalDateTime.now())) {
            return false;
        }
        return MessageDigest.isEqual(
                session.getTicketHash().getBytes(StandardCharsets.UTF_8),
                sha256(ticket).getBytes(StandardCharsets.UTF_8));
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getTicket().getSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("ticket hmac failed", e);
        }
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("sha256 failed", e);
        }
    }

    /** 按创建人列出会话（双入口无差别查询）。 */
    public List<InterviewSessionEntity> listByCreator(String creatorWorkNo) {
        return sessionMapper.selectList(new LambdaQueryWrapper<InterviewSessionEntity>()
                .eq(InterviewSessionEntity::getCreatorWorkNo, creatorWorkNo)
                .orderByDesc(InterviewSessionEntity::getId));
    }
}
