package com.bkanent.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.interview.entity.InterviewAssetEntity;
import com.bkanent.interview.entity.InterviewSessionEntity;
import com.bkanent.interview.entity.InterviewTurnEntity;
import com.bkanent.interview.engine.ArchiveGrader;
import com.bkanent.interview.engine.Sanitizer;
import com.bkanent.interview.mapper.InterviewAssetMapper;
import com.bkanent.interview.mapper.InterviewSessionMapper;
import com.bkanent.interview.mapper.InterviewTurnMapper;
import com.bkanent.interview.runtime.InterviewSessionStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * 访后归集：CLOSING_LOCKED 会话 → 逐字稿资产 + L0–L3 确定性评级。
 */
@Service
public class InterviewCollectionService {

    private static final Logger log = LoggerFactory.getLogger(InterviewCollectionService.class);

    private final InterviewSessionMapper sessionMapper;
    private final InterviewTurnMapper turnMapper;
    private final InterviewAssetMapper assetMapper;
    private final InterviewSessionStateMachine stateMachine;

    public InterviewCollectionService(InterviewSessionMapper sessionMapper,
                                      InterviewTurnMapper turnMapper,
                                      InterviewAssetMapper assetMapper,
                                      InterviewSessionStateMachine stateMachine) {
        this.sessionMapper = sessionMapper;
        this.turnMapper = turnMapper;
        this.assetMapper = assetMapper;
        this.stateMachine = stateMachine;
    }

    /**
     * 归集一个会话为逐字稿资产（冻结快照哈希防覆盖）。
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewAssetEntity collect(InterviewSessionEntity requested) {
        InterviewSessionEntity session = sessionMapper.selectOne(new LambdaQueryWrapper<InterviewSessionEntity>()
                .eq(InterviewSessionEntity::getId, requested.getId())
                .last("LIMIT 1 FOR UPDATE"));
        if (session == null) throw new IllegalArgumentException("session not found: " + requested.getId());
        if (InterviewSessionStateMachine.ARCHIVED.equals(session.getStatus())) {
            return existingAutoAsset(session.getId());
        }
        if (InterviewSessionStateMachine.CLOSING_LOCKED.equals(session.getStatus())) {
            session = stateMachine.transition(session.getId(), InterviewSessionStateMachine.COLLECT_PENDING,
                    InterviewSessionStateMachine.Actor.COMPENSATOR);
        } else if (!InterviewSessionStateMachine.COLLECT_PENDING.equals(session.getStatus())) {
            throw new IllegalStateException("session is not recoverable for collection: " + session.getStatus());
        }
        InterviewAssetEntity existing = findAutoAsset(session.getId());
        if (existing != null) {
            stateMachine.transition(session.getId(), InterviewSessionStateMachine.ARCHIVED,
                    InterviewSessionStateMachine.Actor.COMPENSATOR);
            return existing;
        }

        List<InterviewTurnEntity> turns = turnMapper.selectList(
                new LambdaQueryWrapper<InterviewTurnEntity>()
                        .eq(InterviewTurnEntity::getSessionId, session.getId())
                        .orderByAsc(InterviewTurnEntity::getTurnSeq));

        StringBuilder content = new StringBuilder();
        int respondentTurns = 0;
        for (InterviewTurnEntity turn : turns) {
            String text = Sanitizer.redactCredentials(turn.getSanitizedContent());
            if (text == null || text.isBlank()) {
                continue;
            }
            if ("RESPONDENT".equals(turn.getRole())) {
                respondentTurns++;
                content.append("受访者：").append(text).append("\n\n");
            } else {
                content.append("访谈员：").append(text).append("\n\n");
            }
        }
        String markdown = content.toString();
        int wordCount = markdown.replaceAll("\\s+", "").length();
        String snapshotHash = sha256(markdown);

        // 确定性评级（不用模型）
        boolean subjectClear = session.getCaseId() != null;
        boolean topicFocused = wordCount >= ArchiveGrader.L2_TOPIC_FOCUS_WORDS;
        boolean moduleClosed = respondentTurns >= 6;
        boolean evidenceSufficient = respondentTurns >= ArchiveGrader.L2_MIN_TURNS;
        boolean metadataComplete = session.getCreatorWorkNo() != null
                && session.getReferenceMinutes() != null;
        ArchiveGrader.GradeResult grade = ArchiveGrader.grade(wordCount, subjectClear,
                topicFocused, moduleClosed, evidenceSufficient, turns.size(), metadataComplete);

        InterviewAssetEntity asset = new InterviewAssetEntity();
        asset.setSessionId(session.getId());
        asset.setCaseId(session.getCaseId());
        asset.setSource("AUTO");
        asset.setTitle("访谈会话 #" + session.getId() + " 逐字稿");
        asset.setContent(markdown);
        asset.setSnapshotHash(snapshotHash);
        asset.setContentHash(snapshotHash);
        asset.setContentRevision(1);
        asset.setSanitizationRevision(Sanitizer.VERSION);
        asset.setVisibility("PRIVATE");
        asset.setPublicationRevision(0);
        asset.setArchiveGrade(grade.grade());
        asset.setGradeBasis(ArchiveGrader.gradeBasisJson(grade));
        asset.setTurnCount(turns.size());
        asset.setWordCount(wordCount);
        assetMapper.insert(asset);

        // 补偿器推进 COLLECT_PENDING → ARCHIVED
        stateMachine.transition(session.getId(),
                InterviewSessionStateMachine.ARCHIVED,
                InterviewSessionStateMachine.Actor.COMPENSATOR);
        log.info("Session {} collected as asset {} grade {}", session.getId(), asset.getId(), grade.grade());
        return asset;
    }

    /** 扫描待归集会话（补偿器入口）。 */
    public List<InterviewSessionEntity> scanCollectable() {
        return sessionMapper.selectList(new LambdaQueryWrapper<InterviewSessionEntity>()
                .in(InterviewSessionEntity::getStatus, InterviewSessionStateMachine.CLOSING_LOCKED,
                        InterviewSessionStateMachine.COLLECT_PENDING));
    }

    private InterviewAssetEntity findAutoAsset(Long sessionId) {
        return assetMapper.selectOne(new LambdaQueryWrapper<InterviewAssetEntity>()
                .eq(InterviewAssetEntity::getSessionId, sessionId)
                .eq(InterviewAssetEntity::getSource, "AUTO")
                .eq(InterviewAssetEntity::getDeleted, 0)
                .last("LIMIT 1"));
    }

    private InterviewAssetEntity existingAutoAsset(Long sessionId) {
        InterviewAssetEntity existing = findAutoAsset(sessionId);
        if (existing == null) throw new IllegalStateException("archived session is missing its transcript asset");
        return existing;
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("sha256 failed", e);
        }
    }
}
