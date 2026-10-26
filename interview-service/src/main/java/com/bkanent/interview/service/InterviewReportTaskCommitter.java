package com.bkanent.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bkanent.interview.engine.Sanitizer;
import com.bkanent.interview.entity.InterviewAssetEntity;
import com.bkanent.interview.entity.InterviewReportEntity;
import com.bkanent.interview.entity.InterviewReportTaskEntity;
import com.bkanent.interview.mapper.InterviewAssetMapper;
import com.bkanent.interview.mapper.InterviewReportMapper;
import com.bkanent.interview.mapper.InterviewReportTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Commits report results only while the exact worker lease and accepted source snapshot remain current. */
@Service
public class InterviewReportTaskCommitter {
    private final InterviewReportTaskMapper taskMapper;
    private final InterviewReportMapper reportMapper;
    private final InterviewAssetMapper assetMapper;
    private final InterviewAssetService assetService;

    public InterviewReportTaskCommitter(InterviewReportTaskMapper taskMapper,
                                        InterviewReportMapper reportMapper,
                                        InterviewAssetMapper assetMapper,
                                        InterviewAssetService assetService) {
        this.taskMapper = taskMapper;
        this.reportMapper = reportMapper;
        this.assetMapper = assetMapper;
        this.assetService = assetService;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean complete(Claim claim, InterviewReportEntity report) {
        LocalDateTime now = LocalDateTime.now();
        InterviewReportTaskEntity task = lockClaim(claim, now);
        if (task == null) return false;
        InterviewAssetEntity source = lockAsset(task.getAssetId());
        if (!matchesAcceptedSource(task, source)) {
            blockLockedTask(task, claim, "source_snapshot_changed");
            return false;
        }
        if (report == null || !task.getCaseId().equals(report.getCaseId())
                || !task.getAssetId().equals(report.getAssetId())
                || !task.getSourceContentHash().equals(report.getSourceContentHash())
                || !task.getSourceRevision().equals(report.getSourceRevision())
                || !task.getTemplateVersion().equals(report.getTemplateVersion())) {
            blockLockedTask(task, claim, "report_snapshot_mismatch");
            return false;
        }

        report.setTaskId(task.getId());
        reportMapper.insert(report);
        int updated = taskMapper.update(null, claimWrapper(claim, LocalDateTime.now())
                .set(InterviewReportTaskEntity::getStatus, "SUCCEEDED")
                .set(InterviewReportTaskEntity::getReportId, report.getId())
                .set(InterviewReportTaskEntity::getLeaseOwner, null)
                .set(InterviewReportTaskEntity::getLeaseExpiresAt, null)
                .set(InterviewReportTaskEntity::getErrorClass, null)
                .set(InterviewReportTaskEntity::getErrorMessage, null));
        if (updated != 1) throw new IllegalStateException("report lease changed during commit");
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean block(Claim claim, String reason) {
        LocalDateTime now = LocalDateTime.now();
        InterviewReportTaskEntity task = lockClaim(claim, now);
        if (task == null) return false;
        return blockLockedTask(task, claim, reason);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean fail(Claim claim, String errorClass) {
        LocalDateTime now = LocalDateTime.now();
        InterviewReportTaskEntity task = lockClaim(claim, now);
        if (task == null) return false;
        int retries = (task.getRetries() == null ? 0 : task.getRetries()) + 1;
        int maxRetries = task.getMaxRetries() == null ? 1 : task.getMaxRetries();
        int updated = taskMapper.update(null, claimWrapper(claim, LocalDateTime.now())
                .set(InterviewReportTaskEntity::getRetries, retries)
                .set(InterviewReportTaskEntity::getStatus, retries >= maxRetries ? "BLOCKED" : "RETRYABLE")
                .set(InterviewReportTaskEntity::getErrorClass, retries >= maxRetries ? "blocked" : "retryable")
                .set(InterviewReportTaskEntity::getErrorMessage,
                        "report generation failed: " + safeClassName(errorClass))
                .set(InterviewReportTaskEntity::getLeaseOwner, null)
                .set(InterviewReportTaskEntity::getLeaseExpiresAt, null));
        return updated == 1;
    }

    private InterviewReportTaskEntity lockClaim(Claim claim, LocalDateTime now) {
        return taskMapper.selectOne(new LambdaQueryWrapper<InterviewReportTaskEntity>()
                .eq(InterviewReportTaskEntity::getId, claim.taskId())
                .eq(InterviewReportTaskEntity::getLeaseOwner, claim.owner())
                .eq(InterviewReportTaskEntity::getLeaseEpoch, claim.epoch())
                .eq(InterviewReportTaskEntity::getStatus, "RUNNING")
                .gt(InterviewReportTaskEntity::getLeaseExpiresAt, now)
                .last("LIMIT 1 FOR UPDATE"));
    }

    private InterviewAssetEntity lockAsset(Long assetId) {
        if (assetId == null) return null;
        return assetMapper.selectOne(new LambdaQueryWrapper<InterviewAssetEntity>()
                .eq(InterviewAssetEntity::getId, assetId)
                .last("LIMIT 1 FOR UPDATE"));
    }

    private boolean matchesAcceptedSource(InterviewReportTaskEntity task, InterviewAssetEntity source) {
        if (source == null || task.getCaseId() == null || !task.getCaseId().equals(source.getCaseId())
                || !java.util.Objects.equals(task.getSessionId(), source.getSessionId())
                || task.getSourceContentHash() == null
                || !task.getSourceContentHash().equals(source.getContentHash())
                || task.getSourceRevision() == null || !task.getSourceRevision().equals(source.getContentRevision())
                || task.getSourceSanitizationRevision() == null
                || !task.getSourceSanitizationRevision().equals(source.getSanitizationRevision())
                || source.getSanitizationRevision() == null || source.getSanitizationRevision() < Sanitizer.VERSION) {
            return false;
        }
        return assetService.isUsableReportSource(source);
    }

    private boolean blockLockedTask(InterviewReportTaskEntity task, Claim claim, String reason) {
        int updated = taskMapper.update(null, claimWrapper(claim, LocalDateTime.now())
                .set(InterviewReportTaskEntity::getStatus, "BLOCKED")
                .set(InterviewReportTaskEntity::getErrorClass, "blocked")
                .set(InterviewReportTaskEntity::getErrorMessage, reason)
                .set(InterviewReportTaskEntity::getLeaseOwner, null)
                .set(InterviewReportTaskEntity::getLeaseExpiresAt, null));
        return updated == 1;
    }

    private static LambdaUpdateWrapper<InterviewReportTaskEntity> claimWrapper(Claim claim, LocalDateTime now) {
        return new LambdaUpdateWrapper<InterviewReportTaskEntity>()
                .eq(InterviewReportTaskEntity::getId, claim.taskId())
                .eq(InterviewReportTaskEntity::getLeaseOwner, claim.owner())
                .eq(InterviewReportTaskEntity::getLeaseEpoch, claim.epoch())
                .eq(InterviewReportTaskEntity::getStatus, "RUNNING")
                .gt(InterviewReportTaskEntity::getLeaseExpiresAt, now);
    }

    private static String safeClassName(String value) {
        if (value == null || value.isBlank()) return "Unknown";
        String name = value.replaceAll("[^A-Za-z0-9_$]", "");
        return name.isBlank() ? "Unknown" : name.substring(0, Math.min(name.length(), 80));
    }

    public record Claim(Long taskId, String owner, int epoch) { }
}
