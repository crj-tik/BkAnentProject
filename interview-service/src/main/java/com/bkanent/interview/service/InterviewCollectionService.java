package com.bkanent.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.interview.entity.InterviewAssetEntity;
import com.bkanent.interview.entity.InterviewSessionEntity;
import com.bkanent.interview.entity.InterviewTurnEntity;
import com.bkanent.interview.engine.ArchiveGrader;
import com.bkanent.interview.mapper.InterviewAssetMapper;
import com.bkanent.interview.mapper.InterviewSessionMapper;
import com.bkanent.interview.mapper.InterviewTurnMapper;
import com.bkanent.interview.runtime.InterviewSessionStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
    public InterviewAssetEntity collect(InterviewSessionEntity session) {
        // 补偿器推进 CLOSING_LOCKED → COLLECT_PENDING
        stateMachine.transition(session.getId(),
                InterviewSessionStateMachine.COLLECT_PENDING,
                InterviewSessionStateMachine.Actor.COMPENSATOR);

        List<InterviewTurnEntity> turns = turnMapper.selectList(
                new LambdaQueryWrapper<InterviewTurnEntity>()
                        .eq(InterviewTurnEntity::getSessionId, session.getId())
                        .orderByAsc(InterviewTurnEntity::getTurnSeq));

        StringBuilder content = new StringBuilder();
        int respondentTurns = 0;
        for (InterviewTurnEntity turn : turns) {
            String text = turn.getSanitizedContent() == null ? turn.getContent() : turn.getSanitizedContent();
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
                .eq(InterviewSessionEntity::getStatus, InterviewSessionStateMachine.CLOSING_LOCKED));
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
