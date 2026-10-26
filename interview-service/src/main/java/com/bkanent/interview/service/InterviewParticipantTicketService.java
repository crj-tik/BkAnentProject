package com.bkanent.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.interview.config.InterviewAuthorizationProperties;
import com.bkanent.interview.entity.InterviewParticipantTicketEntity;
import com.bkanent.interview.mapper.InterviewParticipantTicketMapper;
import com.bkanent.interview.security.InterviewAuthorizationScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/** Issues and checks local session-purpose credentials; it never contacts Auth or Redis. */
@Service
public class InterviewParticipantTicketService {
    public static final String MANAGE = "MANAGE";
    public static final String RESPOND = "RESPOND";
    public static final String READ = "READ";
    public static final int DEFAULT_TTL_MINUTES = 15;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final InterviewParticipantTicketMapper mapper;
    private final InterviewAuthorizationProperties properties;
    private final InterviewAuthorizationService authorizationService;

    public InterviewParticipantTicketService(InterviewParticipantTicketMapper mapper,
                                             InterviewAuthorizationProperties properties,
                                             InterviewAuthorizationService authorizationService) {
        this.mapper = mapper;
        this.properties = properties;
        this.authorizationService = authorizationService;
    }

    @Transactional(rollbackFor = Exception.class)
    public IssuedCredential issue(Long sessionId, String purpose) {
        requireEnabled();
        long actorId = InterviewAuthorizationScope.requireActorId();
        authorize(sessionId, purpose);
        String raw = randomCredential();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(Math.min(DEFAULT_TTL_MINUTES,
                Math.max(1, properties.getParticipantTicketTtlMinutes())));

        InterviewParticipantTicketEntity row = new InterviewParticipantTicketEntity();
        row.setSessionId(sessionId);
        row.setUserId(actorId);
        row.setPurpose(purpose);
        row.setTicketHash(sha256(raw));
        row.setExpiresAt(expiresAt);
        row.setRevision(1);
        mapper.insert(row);
        return new IssuedCredential(row.getId(), raw, purpose, expiresAt);
    }

    /** Returns a lease only when the request actor and exact purpose match the stored credential. */
    public CredentialLease validate(Long sessionId, String raw, String purpose) {
        requireEnabled();
        if (raw == null || raw.length() != 43 || !raw.matches("[A-Za-z0-9_-]{43}")) return null;
        long actorId = InterviewAuthorizationScope.requireActorId();
        String hash = sha256(raw);
        InterviewParticipantTicketEntity row = mapper.selectOne(new LambdaQueryWrapper<InterviewParticipantTicketEntity>()
                .eq(InterviewParticipantTicketEntity::getTicketHash, hash)
                .eq(InterviewParticipantTicketEntity::getSessionId, sessionId)
                .eq(InterviewParticipantTicketEntity::getUserId, actorId)
                .eq(InterviewParticipantTicketEntity::getPurpose, purpose)
                .isNull(InterviewParticipantTicketEntity::getRevokedAt)
                .gt(InterviewParticipantTicketEntity::getExpiresAt, LocalDateTime.now())
                .eq(InterviewParticipantTicketEntity::getDeleted, 0)
                .last("LIMIT 1"));
        if (row == null) return null;
        return new CredentialLease(row.getId(), row.getSessionId(), row.getUserId(), row.getPurpose(),
                row.getTicketHash(), row.getRevision(), row.getExpiresAt());
    }

    /** Recheck on long-lived streams so revocation and expiry take effect without an Auth round trip. */
    public boolean isCurrent(CredentialLease lease) {
        if (lease == null || !properties.isEnabled()) return false;
        return mapper.selectCount(new LambdaQueryWrapper<InterviewParticipantTicketEntity>()
                .eq(InterviewParticipantTicketEntity::getId, lease.credentialId())
                .eq(InterviewParticipantTicketEntity::getSessionId, lease.sessionId())
                .eq(InterviewParticipantTicketEntity::getUserId, lease.userId())
                .eq(InterviewParticipantTicketEntity::getPurpose, lease.purpose())
                .eq(InterviewParticipantTicketEntity::getTicketHash, lease.ticketHash())
                .eq(InterviewParticipantTicketEntity::getRevision, lease.revision())
                .isNull(InterviewParticipantTicketEntity::getRevokedAt)
                .gt(InterviewParticipantTicketEntity::getExpiresAt, LocalDateTime.now())
                .eq(InterviewParticipantTicketEntity::getDeleted, 0)) > 0;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean revoke(Long sessionId, Long credentialId) {
        requireEnabled();
        if (sessionId == null || credentialId == null) return false;
        long actorId = InterviewAuthorizationScope.requireActorId();
        InterviewParticipantTicketEntity row = mapper.selectOne(new LambdaQueryWrapper<InterviewParticipantTicketEntity>()
                .eq(InterviewParticipantTicketEntity::getId, credentialId)
                .eq(InterviewParticipantTicketEntity::getSessionId, sessionId)
                .eq(InterviewParticipantTicketEntity::getDeleted, 0)
                .last("LIMIT 1"));
        if (row == null) return false;
        if (!actorIdEquals(row.getUserId(), actorId)) {
            authorizationService.requireInitiator(sessionId, InterviewAuthorizationService.INITIATE);
        }
        int changed = mapper.update(null, new LambdaUpdateWrapper<InterviewParticipantTicketEntity>()
                .set(InterviewParticipantTicketEntity::getRevokedAt, LocalDateTime.now())
                .setSql("revision = revision + 1")
                .eq(InterviewParticipantTicketEntity::getId, credentialId)
                .eq(InterviewParticipantTicketEntity::getSessionId, sessionId)
                .isNull(InterviewParticipantTicketEntity::getRevokedAt)
                .eq(InterviewParticipantTicketEntity::getRevision, row.getRevision())
                .eq(InterviewParticipantTicketEntity::getDeleted, 0));
        return changed == 1;
    }

    private void authorize(Long sessionId, String purpose) {
        if (sessionId == null || sessionId <= 0) throw denied();
        switch (purpose == null ? "" : purpose) {
            case MANAGE -> authorizationService.requireInitiator(sessionId, InterviewAuthorizationService.INITIATE);
            case RESPOND -> authorizationService.requireRespondent(sessionId);
            case READ -> authorizationService.requireRead(sessionId);
            default -> throw denied();
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }

    private static boolean actorIdEquals(Long left, long right) { return left != null && left == right; }

    private static AuthzException denied() { return new AuthzException(AuthzErrorCode.PERMISSION_DENIED); }

    private static String randomCredential() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("credential hash failed", error);
        }
    }

    public record IssuedCredential(Long credentialId, String credential, String purpose,
                                   LocalDateTime expiresAt) { }
    public record CredentialLease(Long credentialId, Long sessionId, Long userId, String purpose,
                                  String ticketHash, Integer revision, LocalDateTime expiresAt) { }
}
