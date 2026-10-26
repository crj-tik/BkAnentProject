package com.bkanent.interview.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.interview.engine.ArchiveGrader;
import com.bkanent.interview.engine.Sanitizer;
import com.bkanent.interview.entity.InterviewAssetEntity;
import com.bkanent.interview.entity.InterviewCaseEntity;
import com.bkanent.interview.entity.InterviewSessionEntity;
import com.bkanent.interview.mapper.InterviewAssetMapper;
import com.bkanent.interview.mapper.InterviewCaseMapper;
import com.bkanent.interview.mapper.InterviewSessionMapper;
import com.bkanent.interview.runtime.InterviewSessionStateMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Private-by-default transcript lifecycle with explicit publish and source checks. */
@Service
public class InterviewAssetService {
    public static final String PRIVATE = "PRIVATE";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String SUBMIT = "interview.asset.submit";
    public static final String PUBLISH = "interview.asset.publish";
    public static final String REGRADE = "interview.asset.regrade";

    private final InterviewAssetMapper assetMapper;
    private final InterviewCaseMapper caseMapper;
    private final InterviewSessionMapper sessionMapper;
    private final InterviewAuthorizationService authorizationService;

    public InterviewAssetService(InterviewAssetMapper assetMapper, InterviewCaseMapper caseMapper,
                                 InterviewSessionMapper sessionMapper,
                                 InterviewAuthorizationService authorizationService) {
        this.assetMapper = assetMapper;
        this.caseMapper = caseMapper;
        this.sessionMapper = sessionMapper;
        this.authorizationService = authorizationService;
    }

    @Transactional(rollbackFor = Exception.class)
    public InterviewAssetEntity submitTranscript(Long caseId, String title, String rawContent) {
        authorizationService.requireInitiatorForCase(caseId, SUBMIT);
        if (rawContent == null || rawContent.isBlank() || rawContent.length() > 1_000_000) {
            throw new IllegalArgumentException("transcript content is missing or too large");
        }
        InterviewCaseEntity caseEntity = requireCase(caseId);
        String credentialRedactedRaw = Sanitizer.redactCredentials(rawContent);
        Sanitizer.SanitizeResult sanitized = Sanitizer.sanitize(credentialRedactedRaw, knownPrivateValues(caseEntity));
        String content = sanitized.sanitizedText();
        int turns = countTurns(content);
        int words = countWords(content);
        ArchiveGrader.GradeResult grade = grade(content, caseEntity, null, turns, words);

        InterviewAssetEntity asset = new InterviewAssetEntity();
        asset.setCaseId(caseId);
        asset.setSessionId(null);
        asset.setSource("UPLOAD");
        asset.setTitle(title == null || title.isBlank() ? "外部提交逐字稿" : Sanitizer.redactCredentials(title.trim()));
        asset.setContent(content);
        asset.setRawContent(credentialRedactedRaw);
        asset.setContentHash(sha256(content));
        asset.setSnapshotHash(asset.getContentHash());
        asset.setContentRevision(1);
        asset.setSanitizationRevision(Sanitizer.VERSION);
        asset.setVisibility(PRIVATE);
        asset.setPublicationRevision(0);
        asset.setArchiveGrade(grade.grade());
        asset.setGradeBasis(ArchiveGrader.gradeBasisJson(grade));
        asset.setTurnCount(turns);
        asset.setWordCount(words);
        assetMapper.insert(asset);
        return asset;
    }

    @Transactional(rollbackFor = Exception.class)
    public InterviewAssetEntity regrade(Long assetId) {
        authorizationService.requireOperation(REGRADE);
        InterviewAssetEntity asset = requireAsset(assetId);
        authorizationService.requireInitiatorForCase(asset.getCaseId(), REGRADE);
        SourceFacts facts = requireUsableSource(asset);
        ArchiveGrader.GradeResult grade = grade(asset.getContent(), facts.caseEntity(), facts.session(),
                asset.getTurnCount() == null ? 0 : asset.getTurnCount(),
                asset.getWordCount() == null ? countWords(asset.getContent()) : asset.getWordCount());
        InterviewAssetEntity update = new InterviewAssetEntity();
        update.setArchiveGrade(grade.grade());
        update.setGradeBasis(ArchiveGrader.gradeBasisJson(grade));
        update.setVisibility(PRIVATE);
        update.setApprovedByUserId(null);
        update.setPublishedAt(null);
        int nextPublicationRevision = (asset.getPublicationRevision() == null ? 0 : asset.getPublicationRevision())
                + (PUBLISHED.equals(asset.getVisibility()) ? 1 : 0);
        update.setPublicationRevision(nextPublicationRevision);
        int changed = assetMapper.update(update, new LambdaUpdateWrapper<InterviewAssetEntity>()
                .eq(InterviewAssetEntity::getId, assetId)
                .eq(InterviewAssetEntity::getContentRevision, asset.getContentRevision())
                .eq(InterviewAssetEntity::getContentHash, asset.getContentHash())
                .eq(InterviewAssetEntity::getDeleted, 0));
        if (changed != 1) throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
        asset.setArchiveGrade(grade.grade());
        asset.setGradeBasis(ArchiveGrader.gradeBasisJson(grade));
        asset.setVisibility(PRIVATE);
        asset.setApprovedByUserId(null);
        asset.setPublishedAt(null);
        asset.setPublicationRevision(nextPublicationRevision);
        return asset;
    }

    @Transactional(rollbackFor = Exception.class)
    public InterviewAssetEntity publish(Long assetId) {
        authorizationService.requireOperation(PUBLISH);
        InterviewAssetEntity asset = requireAsset(assetId);
        authorizationService.requireInitiatorForCase(asset.getCaseId(), PUBLISH);
        SourceFacts facts = requireUsableSource(asset);
        if (asset.getSanitizationRevision() == null || asset.getSanitizationRevision() < Sanitizer.VERSION
                || Sanitizer.containsCredentialShape(asset.getTitle())
                || Sanitizer.containsCredentialShape(asset.getContent())
                || !ArchiveGrader.isPubliclyVisible(asset.getArchiveGrade())
                || !sha256(asset.getContent()).equals(asset.getContentHash())) {
            throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        }
        if (PUBLISHED.equals(asset.getVisibility())) return asset;
        long actorId = com.bkanent.interview.security.InterviewAuthorizationScope.requireActorId();
        int revision = asset.getPublicationRevision() == null ? 0 : asset.getPublicationRevision();
        LocalDateTime publishedAt = LocalDateTime.now();
        int changed = assetMapper.update(null, new LambdaUpdateWrapper<InterviewAssetEntity>()
                .set(InterviewAssetEntity::getVisibility, PUBLISHED)
                .set(InterviewAssetEntity::getApprovedByUserId, actorId)
                .set(InterviewAssetEntity::getPublishedAt, publishedAt)
                .set(InterviewAssetEntity::getPublicationRevision, revision + 1)
                .eq(InterviewAssetEntity::getId, assetId)
                .eq(InterviewAssetEntity::getCaseId, asset.getCaseId())
                .eq(InterviewAssetEntity::getContentHash, asset.getContentHash())
                .eq(InterviewAssetEntity::getContentRevision, asset.getContentRevision())
                .eq(InterviewAssetEntity::getPublicationRevision, revision)
                .eq(InterviewAssetEntity::getVisibility, PRIVATE)
                .eq(InterviewAssetEntity::getDeleted, 0));
        if (changed != 1) throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
        asset.setVisibility(PUBLISHED);
        asset.setApprovedByUserId(actorId);
        asset.setPublishedAt(publishedAt);
        asset.setPublicationRevision(revision + 1);
        return asset;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean revokePublication(Long assetId) {
        authorizationService.requireOperation(PUBLISH);
        InterviewAssetEntity asset = requireAsset(assetId);
        authorizationService.requireInitiatorForCase(asset.getCaseId(), PUBLISH);
        if (!PUBLISHED.equals(asset.getVisibility())) return true;
        int revision = asset.getPublicationRevision() == null ? 0 : asset.getPublicationRevision();
        int changed = assetMapper.update(null, new LambdaUpdateWrapper<InterviewAssetEntity>()
                .set(InterviewAssetEntity::getVisibility, PRIVATE)
                .set(InterviewAssetEntity::getApprovedByUserId, null)
                .set(InterviewAssetEntity::getPublishedAt, null)
                .set(InterviewAssetEntity::getPublicationRevision, revision + 1)
                .eq(InterviewAssetEntity::getId, assetId)
                .eq(InterviewAssetEntity::getVisibility, PUBLISHED)
                .eq(InterviewAssetEntity::getPublicationRevision, revision)
                .eq(InterviewAssetEntity::getDeleted, 0));
        return changed == 1;
    }

    public boolean isPubliclyEligible(InterviewAssetEntity asset) {
        if (asset == null || !PUBLISHED.equals(asset.getVisibility())
                || !ArchiveGrader.isPubliclyVisible(asset.getArchiveGrade())
                || asset.getSanitizationRevision() == null || asset.getSanitizationRevision() < Sanitizer.VERSION
                || asset.getContentHash() == null || asset.getContent() == null
                || Sanitizer.containsCredentialShape(asset.getTitle())
                || Sanitizer.containsCredentialShape(asset.getContent())
                || !sha256(asset.getContent()).equals(asset.getContentHash())) return false;
        try {
            requireUsableSource(asset);
            return true;
        } catch (AuthzException | IllegalArgumentException invalidSource) {
            return false;
        }
    }

    /** Requires a source suitable for private report generation, regardless of publication state. */
    public InterviewAssetEntity requireReportSource(Long caseId, Long assetId) {
        InterviewAssetEntity asset = requireAsset(assetId);
        if (caseId == null || !caseId.equals(asset.getCaseId()) || !isUsableReportSource(asset)) {
            throw new IllegalArgumentException("report source is unavailable");
        }
        return asset;
    }

    /** Rechecks persisted case/session facts and the sanitized body hash; raw content is never a fallback. */
    public boolean isUsableReportSource(InterviewAssetEntity asset) {
        if (asset == null || asset.getContent() == null || asset.getContent().isBlank()
                || asset.getContentHash() == null || asset.getContentRevision() == null
                || asset.getContentRevision() <= 0 || asset.getSanitizationRevision() == null
                || asset.getSanitizationRevision() < Sanitizer.VERSION
                || Sanitizer.containsCredentialShape(asset.getContent())
                || !sha256(asset.getContent()).equals(asset.getContentHash())) return false;
        try {
            requireUsableSource(asset);
            return true;
        } catch (AuthzException | IllegalArgumentException invalidSource) {
            return false;
        }
    }

    public InterviewAssetEntity requireAsset(Long assetId) {
        InterviewAssetEntity asset = assetId == null ? null : assetMapper.selectById(assetId);
        if (asset == null || Integer.valueOf(1).equals(asset.getDeleted())) {
            throw new IllegalArgumentException("asset not found");
        }
        return asset;
    }

    private SourceFacts requireUsableSource(InterviewAssetEntity asset) {
        InterviewCaseEntity caseEntity = requireCase(asset.getCaseId());
        InterviewSessionEntity session = null;
        if (asset.getSessionId() != null) {
            session = sessionMapper.selectById(asset.getSessionId());
            if (session == null || !asset.getCaseId().equals(session.getCaseId())
                    || !InterviewSessionStateMachine.ARCHIVED.equals(session.getStatus())) {
                throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
            }
        } else if (!"UPLOAD".equals(asset.getSource())) {
            throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        }
        return new SourceFacts(caseEntity, session);
    }

    private InterviewCaseEntity requireCase(Long caseId) {
        InterviewCaseEntity caseEntity = caseId == null ? null : caseMapper.selectById(caseId);
        if (caseEntity == null || Integer.valueOf(1).equals(caseEntity.getDeleted())) {
            throw new IllegalArgumentException("case not found");
        }
        return caseEntity;
    }

    private ArchiveGrader.GradeResult grade(String content, InterviewCaseEntity caseEntity,
                                            InterviewSessionEntity session, int turns, int words) {
        boolean subjectClear = caseEntity.getScene() != null && caseEntity.getRespondentRole() != null
                && caseEntity.getObjective() != null && !caseEntity.getObjective().isBlank();
        boolean topicFocused = words >= ArchiveGrader.L2_TOPIC_FOCUS_WORDS;
        boolean moduleClosed = session != null && InterviewSessionStateMachine.ARCHIVED.equals(session.getStatus())
                || content != null && (content.contains("[CLOSED]") || content.contains("感谢今天的分享")
                || content.contains("谢谢今天的分享") || content.contains("访谈结束"));
        boolean evidenceSufficient = turns >= ArchiveGrader.L2_MIN_TURNS
                && content != null && (content.contains("受访者：") || content.contains("答："));
        boolean metadataComplete = subjectClear && present(caseEntity.getDivisionName())
                && present(caseEntity.getRegionName()) && present(caseEntity.getBusinessDistrict())
                && (session == null || session.getReferenceMinutes() != null);
        return ArchiveGrader.grade(words, subjectClear, topicFocused, moduleClosed,
                evidenceSufficient, turns, metadataComplete);
    }

    private static List<String> knownPrivateValues(InterviewCaseEntity caseEntity) {
        List<String> values = new ArrayList<>();
        values.add(caseEntity.getRespondentName());
        values.add(caseEntity.getCreatorWorkNo());
        return values;
    }

    private int countTurns(String content) {
        int count = 0;
        for (String line : content.split("\\R")) {
            String value = line.trim();
            if (value.startsWith("受访者：") || value.startsWith("访谈员：")
                    || value.startsWith("问：") || value.startsWith("答：")) count++;
        }
        return Math.max(count, content.split("\\R\\s*\\R").length);
    }

    private static int countWords(String value) {
        return value == null ? 0 : value.replaceAll("\\s+", "").length();
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("asset content hash failed", error);
        }
    }

    private record SourceFacts(InterviewCaseEntity caseEntity, InterviewSessionEntity session) { }
}
