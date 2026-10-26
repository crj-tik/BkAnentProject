package com.bkanent.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bkanent.interview.entity.InterviewParticipantEntity;
import com.bkanent.interview.mapper.InterviewParticipantMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class InterviewParticipantService {
    public static final String CREATE = "CREATE";
    public static final String RESPOND = "RESPOND";
    public static final String PENDING = "PENDING";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String REVOKED = "REVOKED";

    private final InterviewParticipantMapper mapper;

    public InterviewParticipantService(InterviewParticipantMapper mapper) { this.mapper = mapper; }

    @Transactional(rollbackFor = Exception.class)
    public void createSessionRelations(Long sessionId, Long creatorUserId, Long respondentUserId) {
        if (sessionId == null || sessionId <= 0 || creatorUserId == null || creatorUserId <= 0
                || respondentUserId != null && respondentUserId <= 0) {
            throw new IllegalArgumentException("invalid participant identity");
        }
        InterviewParticipantEntity creator = relation(sessionId, creatorUserId, CREATE, CONFIRMED, creatorUserId,
                LocalDateTime.now());
        mapper.insert(creator);
        if (respondentUserId != null) {
            mapper.insert(relation(sessionId, respondentUserId, RESPOND, PENDING, creatorUserId, null));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean inviteRespondent(Long sessionId, Long inviterUserId, Long respondentUserId) {
        if (sessionId == null || sessionId <= 0 || inviterUserId == null || inviterUserId <= 0
                || respondentUserId == null || respondentUserId <= 0) return false;
        if (!hasConfirmedRelation(sessionId, inviterUserId, CREATE)) return false;
        if (mapper.selectCount(new LambdaQueryWrapper<InterviewParticipantEntity>()
                .eq(InterviewParticipantEntity::getSessionId, sessionId)
                .eq(InterviewParticipantEntity::getUserId, respondentUserId)
                .eq(InterviewParticipantEntity::getRelation, RESPOND)) > 0) return false;
        mapper.insert(relation(sessionId, respondentUserId, RESPOND, PENDING, inviterUserId, null));
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean confirmRespondent(Long sessionId, Long authenticatedUserId) {
        if (sessionId == null || sessionId <= 0 || authenticatedUserId == null || authenticatedUserId <= 0) return false;
        int updated = mapper.update(null, new LambdaUpdateWrapper<InterviewParticipantEntity>()
                .set(InterviewParticipantEntity::getState, CONFIRMED)
                .set(InterviewParticipantEntity::getConfirmedAt, LocalDateTime.now())
                .setSql("revision = revision + 1")
                .eq(InterviewParticipantEntity::getSessionId, sessionId)
                .eq(InterviewParticipantEntity::getUserId, authenticatedUserId)
                .eq(InterviewParticipantEntity::getRelation, RESPOND)
                .eq(InterviewParticipantEntity::getState, PENDING)
                .eq(InterviewParticipantEntity::getDeleted, 0));
        if (updated > 0) return true;
        return mapper.selectCount(new LambdaQueryWrapper<InterviewParticipantEntity>()
                .eq(InterviewParticipantEntity::getSessionId, sessionId)
                .eq(InterviewParticipantEntity::getUserId, authenticatedUserId)
                .eq(InterviewParticipantEntity::getRelation, RESPOND)
                .eq(InterviewParticipantEntity::getState, CONFIRMED)) > 0;
    }

    public boolean hasConfirmedRelation(Long sessionId, Long userId, String relation) {
        if (sessionId == null || userId == null || relation == null) return false;
        return mapper.selectCount(new LambdaQueryWrapper<InterviewParticipantEntity>()
                .eq(InterviewParticipantEntity::getSessionId, sessionId)
                .eq(InterviewParticipantEntity::getUserId, userId)
                .eq(InterviewParticipantEntity::getRelation, relation)
                .eq(InterviewParticipantEntity::getState, CONFIRMED)) > 0;
    }

    public boolean hasConfirmedAnyRelation(Long sessionId, Long userId) {
        if (sessionId == null || userId == null) return false;
        return mapper.selectCount(new LambdaQueryWrapper<InterviewParticipantEntity>()
                .eq(InterviewParticipantEntity::getSessionId, sessionId)
                .eq(InterviewParticipantEntity::getUserId, userId)
                .eq(InterviewParticipantEntity::getState, CONFIRMED)) > 0;
    }

    public List<Long> confirmedSessionIds(Long userId) {
        if (userId == null || userId <= 0) return List.of();
        return mapper.selectList(new LambdaQueryWrapper<InterviewParticipantEntity>()
                        .select(InterviewParticipantEntity::getSessionId)
                        .eq(InterviewParticipantEntity::getUserId, userId)
                        .eq(InterviewParticipantEntity::getState, CONFIRMED)
                        .orderByAsc(InterviewParticipantEntity::getSessionId))
                .stream().map(InterviewParticipantEntity::getSessionId).distinct().toList();
    }

    public List<InterviewParticipantEntity> pendingInvitations(Long userId) {
        if (userId == null || userId <= 0) return List.of();
        return mapper.selectList(new LambdaQueryWrapper<InterviewParticipantEntity>()
                .eq(InterviewParticipantEntity::getUserId, userId)
                .eq(InterviewParticipantEntity::getRelation, RESPOND)
                .eq(InterviewParticipantEntity::getState, PENDING)
                .orderByDesc(InterviewParticipantEntity::getId));
    }

    private static InterviewParticipantEntity relation(Long sessionId, Long userId, String relation,
                                                        String state, Long invitedByUserId,
                                                        LocalDateTime confirmedAt) {
        InterviewParticipantEntity value = new InterviewParticipantEntity();
        value.setSessionId(sessionId);
        value.setUserId(userId);
        value.setRelation(relation);
        value.setState(state);
        value.setInvitedByUserId(invitedByUserId);
        value.setConfirmedAt(confirmedAt);
        value.setRevision(1);
        return value;
    }
}
