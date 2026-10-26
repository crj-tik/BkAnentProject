package com.bkanent.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.interview.config.InterviewAuthorizationProperties;
import com.bkanent.interview.entity.InterviewSessionEntity;
import com.bkanent.interview.entity.InterviewDirectorCommandEntity;
import com.bkanent.interview.mapper.InterviewSessionMapper;
import com.bkanent.interview.security.InterviewAuthorizationScope;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;

/** Central resource-policy checks for versioned Interview requests. */
@Service
public class InterviewAuthorizationService {
    public static final String READ = "interview.read";
    public static final String READ_ALL = "interview.read.all";
    public static final String INITIATE = "interview.initiate";
    public static final String RESPOND = "interview.respond";
    public static final String ASSIST = "interview.assist";
    public static final String END = "interview.end";
    public static final String REPORT_GENERATE = "interview.report.generate";

    private final InterviewAuthorizationProperties properties;
    private final InterviewSessionMapper sessionMapper;
    private final InterviewParticipantService participantService;

    public InterviewAuthorizationService(InterviewAuthorizationProperties properties,
                                         InterviewSessionMapper sessionMapper,
                                         InterviewParticipantService participantService) {
        this.properties = properties;
        this.sessionMapper = sessionMapper;
        this.participantService = participantService;
    }

    public boolean isEnabled() { return properties.isEnabled(); }

    public void requireInitiator(Long sessionId, String operation) {
        requireEnabled();
        requireOperation(operation);
        long actorId = InterviewAuthorizationScope.requireActorId();
        if (participantService.hasConfirmedRelation(
                sessionId, actorId, InterviewParticipantService.CREATE)) return;
        deny();
    }

    public void requireInitiatorForCase(Long caseId, String operation) {
        requireEnabled();
        requireOperation(operation);
        InterviewSessionEntity session = sessionMapper.selectOne(new LambdaQueryWrapper<InterviewSessionEntity>()
                .eq(InterviewSessionEntity::getCaseId, caseId)
                .orderByDesc(InterviewSessionEntity::getId)
                .last("LIMIT 1"));
        if (session == null) deny();
        requireInitiator(session.getId(), operation);
    }

    public void requireRespondent(Long sessionId) {
        requireEnabled();
        requireOperation(RESPOND);
        long actorId = InterviewAuthorizationScope.requireActorId();
        if (participantService.hasConfirmedRelation(sessionId, actorId, InterviewParticipantService.RESPOND)) return;
        deny();
    }

    public void requireRead(Long sessionId) {
        requireEnabled();
        boolean read = InterviewAuthorizationScope.hasPermission(READ)
                || InterviewAuthorizationScope.hasPermission(RESPOND)
                || InterviewAuthorizationScope.hasPermission(READ_ALL);
        if (!read) deny();
        if (isBusinessWideReader()) return;
        long actorId = InterviewAuthorizationScope.requireActorId();
        if (participantService.hasConfirmedAnyRelation(sessionId, actorId)) return;
        deny();
    }

    /** Null means a business-wide reader; an empty list means no owned sessions. */
    public List<Long> visibleSessionIds() {
        requireEnabled();
        if (isBusinessWideReader()) return null;
        if (!InterviewAuthorizationScope.hasPermission(READ)
                && !InterviewAuthorizationScope.hasPermission(RESPOND)) deny();
        long actorId = InterviewAuthorizationScope.requireActorId();
        return participantService.confirmedSessionIds(actorId);
    }

    /** Null means a business-wide reader; an empty list means no owned cases. */
    public List<Long> visibleCaseIds() {
        List<Long> sessionIds = visibleSessionIds();
        if (sessionIds == null) return null;
        if (sessionIds.isEmpty()) return List.of();
        return sessionMapper.selectList(new LambdaQueryWrapper<InterviewSessionEntity>()
                        .select(InterviewSessionEntity::getCaseId)
                        .in(InterviewSessionEntity::getId, sessionIds))
                .stream().map(InterviewSessionEntity::getCaseId).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
    }

    public ActorFacts currentActorFacts() {
        requireEnabled();
        return new ActorFacts(InterviewAuthorizationScope.requireActorId(),
                InterviewAuthorizationScope.permissionRevision(), InterviewAuthorizationScope.credentialEpoch(),
                InterviewAuthorizationScope.expiresAtEpochSecond(), isBusinessWideReader());
    }

    public void stampAuthorizedCommand(InterviewDirectorCommandEntity command) {
        if (!isEnabled()) return;
        String operation = "END_SESSION".equals(command.getCommand()) ? END : ASSIST;
        requireInitiator(command.getSessionId(), operation);
        ActorFacts actor = currentActorFacts();
        command.setRequestedByUserId(actor.userId());
        command.setPermissionRevision(actor.permissionRevision());
        command.setCredentialEpoch(actor.credentialEpoch());
        command.setAuthorizationExpiresAt(java.time.Instant.ofEpochSecond(actor.expiresAtEpochSecond())
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
        command.setBusinessWide(actor.businessWide());
        command.setAuthorizationOperation(operation);
    }

    public record ActorFacts(Long userId, Long permissionRevision, Long credentialEpoch,
                             Long expiresAtEpochSecond, boolean businessWide) { }

    public void requireOperation(String operation) {
        requireEnabled();
        InterviewAuthorizationScope.requirePermission(operation);
    }

    private void requireEnabled() {
        if (!isEnabled()) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }

    public boolean isBusinessWideReader() {
        return isEnabled() && InterviewAuthorizationScope.hasPermission(READ_ALL);
    }

    public InterviewSessionEntity requireSession(Long sessionId) {
        InterviewSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null) deny();
        return session;
    }

    private static void deny() {
        throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
    }
}
