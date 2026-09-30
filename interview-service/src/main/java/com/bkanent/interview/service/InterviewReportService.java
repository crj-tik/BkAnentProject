package com.bkanent.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.interview.config.InterviewReportProperties;
import com.bkanent.interview.entity.InterviewAssetEntity;
import com.bkanent.interview.entity.InterviewReportEntity;
import com.bkanent.interview.entity.InterviewReportTaskEntity;
import com.bkanent.interview.engine.EvidenceVerifier;
import com.bkanent.interview.mapper.InterviewAssetMapper;
import com.bkanent.interview.mapper.InterviewReportMapper;
import com.bkanent.interview.mapper.InterviewReportTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 案例卡报告：持久化任务（租约 + 幂等 + 进程重启可续跑）+ AI 抽取归纳
 * + 服务端证据核验（原声逐字命中、降级转述、缺失三分类）。
 */
@Service
public class InterviewReportService {

    private static final Logger log = LoggerFactory.getLogger(InterviewReportService.class);

    private static final String CASE_CARD_SYSTEM_PROMPT = """
            You are a real-estate case report writer. Based ONLY on the provided interview transcript,
            produce a JSON object in this exact format:
            {
              "coreFinding": {"oneLiner": "string", "summary": ["5 segments"]},
              "timeline": [{"point": "string", "turningPoint": true}],
              "coreStrategies": [{"signal": "string", "action": "string", "result": "string", "boundary": "string"}],
              "persona": {"quadrant": ["candidate personas"]},
              "quotes": ["verbatim respondent quotes ONLY, copied character-by-character from the transcript"]
            }
            Rules: quotes must be copied verbatim from the transcript (no rewriting, no stitching).
            Timeline must contain at most 6 points. Do not invent facts.
            """;

    private final InterviewReportTaskMapper taskMapper;
    private final InterviewReportMapper reportMapper;
    private final InterviewAssetMapper assetMapper;
    private final ChatModel chatModel;
    private final InterviewReportProperties properties;
    private final ObjectMapper objectMapper;

    public InterviewReportService(InterviewReportTaskMapper taskMapper,
                                  InterviewReportMapper reportMapper,
                                  InterviewAssetMapper assetMapper,
                                  ChatModel chatModel,
                                  InterviewReportProperties properties,
                                  ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.reportMapper = reportMapper;
        this.assetMapper = assetMapper;
        this.chatModel = chatModel;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交案例卡任务：输入快照哈希幂等——相同输入复用同一任务；
     * 已成功且有报告直接返回（不再调模型）。
     */
    public Map<String, Object> submitCaseCardTask(Long caseId, Long assetId) {
        String snapshotHash = sha256("CASE_CARD:" + caseId + ":" + assetId);

        InterviewReportTaskEntity existing = taskMapper.selectOne(
                new LambdaQueryWrapper<InterviewReportTaskEntity>()
                        .eq(InterviewReportTaskEntity::getTaskType, "CASE_CARD")
                        .eq(InterviewReportTaskEntity::getInputSnapshotHash, snapshotHash));
        if (existing != null) {
            // 已成功的任务且报告已存在 → 幂等完成凭据，直接返回
            if ("SUCCEEDED".equals(existing.getStatus()) && existing.getReportId() != null) {
                InterviewReportEntity report = reportMapper.selectById(existing.getReportId());
                Map<String, Object> view = taskView(existing, "idempotent-hit");
                if (report != null) {
                    view.put("score", report.getScore());
                    view.put("replicabilityLevel", report.getReplicabilityLevel());
                }
                return view;
            }
            return taskView(existing, "reused");
        }

        InterviewReportTaskEntity task = new InterviewReportTaskEntity();
        task.setCaseId(caseId);
        task.setAssetId(assetId);
        task.setTaskType("CASE_CARD");
        task.setStatus("PENDING");
        task.setInputSnapshotHash(snapshotHash);
        task.setLeaseEpoch(0);
        task.setRetries(0);
        task.setMaxRetries(properties.getMaxRetries());
        taskMapper.insert(task);
        return taskView(task, "created");
    }

    Map<String, Object> taskView(InterviewReportTaskEntity task, String disposition) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("taskId", task.getId());
        view.put("status", task.getStatus());
        view.put("disposition", disposition);
        view.put("reportId", task.getReportId());
        view.put("errorClass", task.getErrorClass());
        return view;
    }

    /** 租约执行器：扫描可执行任务——PENDING/RETRYABLE 无租约或租约过期，
     * 以及 RUNNING 但租约已过期（持有者进程崩溃，防任务卡死）。 */
    @Scheduled(fixedDelay = 15000)
    public void runLeasedTasks() {
        List<InterviewReportTaskEntity> runnable = taskMapper.selectList(
                new LambdaQueryWrapper<InterviewReportTaskEntity>()
                        .in(InterviewReportTaskEntity::getStatus, "PENDING", "RETRYABLE", "RUNNING")
                        .and(w -> w.isNull(InterviewReportTaskEntity::getLeaseExpiresAt)
                                .or()
                                .lt(InterviewReportTaskEntity::getLeaseExpiresAt, LocalDateTime.now()))
                        .last("LIMIT 5"));
        for (InterviewReportTaskEntity task : runnable) {
            acquireAndRun(task);
        }
    }

    private void acquireAndRun(InterviewReportTaskEntity task) {
        // 租约获取（乐观）：lease_epoch 防旧执行复活
        InterviewReportTaskEntity acquire = new InterviewReportTaskEntity();
        acquire.setId(task.getId());
        acquire.setStatus("RUNNING");
        acquire.setLeaseOwner(UUID.randomUUID().toString());
        acquire.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(properties.getLeaseMinutes()));
        acquire.setLeaseEpoch((task.getLeaseEpoch() == null ? 0 : task.getLeaseEpoch()) + 1);
        int rows = taskMapper.update(acquire, new LambdaQueryWrapper<InterviewReportTaskEntity>()
                .eq(InterviewReportTaskEntity::getId, task.getId())
                .eq(InterviewReportTaskEntity::getLeaseEpoch, task.getLeaseEpoch() == null ? 0 : task.getLeaseEpoch()));
        if (rows == 0) {
            return; // 其他执行器抢到
        }
        try {
            execute(task);
        } catch (Exception e) {
            log.warn("Report task {} failed: {}", task.getId(), e.getMessage());
            markRetryable(task.getId(), e);
        }
    }

    /** 幂等执行：已成功直接返回。 */
    private void execute(InterviewReportTaskEntity task) throws Exception {
        InterviewReportEntity existing = reportMapper.selectOne(
                new LambdaQueryWrapper<InterviewReportEntity>()
                        .eq(InterviewReportEntity::getCaseId, task.getCaseId())
                        .eq(InterviewReportEntity::getInputSnapshotHash, task.getInputSnapshotHash()));
        if (existing != null) {
            InterviewReportTaskEntity done = new InterviewReportTaskEntity();
            done.setId(task.getId());
            done.setStatus("SUCCEEDED");
            done.setReportId(existing.getId());
            taskMapper.updateById(done);
            return;
        }

        InterviewAssetEntity asset = task.getAssetId() == null ? null : assetMapper.selectById(task.getAssetId());
        if (asset == null) {
            InterviewReportTaskEntity blocked = new InterviewReportTaskEntity();
            blocked.setId(task.getId());
            blocked.setStatus("BLOCKED");
            blocked.setErrorClass("blocked");
            blocked.setErrorMessage("source asset missing or not provided");
            taskMapper.updateById(blocked);
            return;
        }

        // AI 只做抽取归纳
        String llmResponse = chatModel.call(new Prompt(List.of(
                new SystemMessage(CASE_CARD_SYSTEM_PROMPT),
                new UserMessage(asset.getContent())))).getResult().getOutput().getText();
        Map<String, Object> parsed = parseJson(llmResponse);

        // 服务端证据核验（脱敏域内比对）
        List<String> quotes = toStringList(parsed.get("quotes"));
        List<EvidenceVerifier.VerifiedQuote> verified = EvidenceVerifier.verifyAll(quotes, asset.getContent());
        long verbatim = EvidenceVerifier.verbatimCount(verified);

        // 可复制性三轴 + 质量分（缺失 1 项封顶 79；成功案例不得直接判 validated）
        boolean allVerbatim = verbatim == quotes.size() && !quotes.isEmpty();
        String replicability = allVerbatim && "L3".equals(asset.getArchiveGrade()) ? "L2" : "L1";
        int score = allVerbatim ? 85 : Math.min(79, 60 + (int) verbatim * 5);

        InterviewReportEntity report = new InterviewReportEntity();
        report.setCaseId(task.getCaseId());
        report.setSessionId(asset.getSessionId());
        report.setAssetId(asset.getId());
        report.setReportType("CASE_CARD");
        report.setReportJson(objectMapper.writeValueAsString(parsed));
        report.setEvidenceVerification(objectMapper.writeValueAsString(verified));
        Map<String, EvidenceVerifier.MissingClass> missing = new LinkedHashMap<>();
        for (EvidenceVerifier.VerifiedQuote v : verified) {
            if (v.status() == EvidenceVerifier.QuoteStatus.PARAPHRASE) {
                missing.put("引用未逐字命中（已降级转述）", EvidenceVerifier.MissingClass.INTERVIEW_GAP);
            }
        }
        report.setMissingItems(EvidenceVerifier.missingJson(missing));
        report.setScore(score);
        report.setReplicabilityLevel(replicability);
        report.setInputSnapshotHash(task.getInputSnapshotHash());
        reportMapper.insert(report);

        InterviewReportTaskEntity done = new InterviewReportTaskEntity();
        done.setId(task.getId());
        done.setStatus("SUCCEEDED");
        done.setReportId(report.getId());
        taskMapper.updateById(done);
        log.info("Report task {} succeeded: report={} score={} replicability={}",
                task.getId(), report.getId(), score, replicability);
    }

    private void markRetryable(Long taskId, Exception e) {
        InterviewReportTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        int retries = (task.getRetries() == null ? 0 : task.getRetries()) + 1;
        int maxRetries = task.getMaxRetries() == null ? properties.getMaxRetries() : task.getMaxRetries();
        InterviewReportTaskEntity update = new InterviewReportTaskEntity();
        update.setId(taskId);
        update.setRetries(retries);
        update.setLeaseOwner(null);
        update.setLeaseExpiresAt(null);
        if (retries >= maxRetries) {
            update.setStatus("BLOCKED");
            update.setErrorClass("blocked");
        } else {
            update.setStatus("RETRYABLE");
            update.setErrorClass("retryable");
        }
        update.setErrorMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        taskMapper.updateById(update);
    }

    private Map<String, Object> parseJson(String llmResponse) throws Exception {
        String trimmed = llmResponse == null ? "" : llmResponse.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[a-z]*\\n?", "").replaceFirst("```$", "").trim();
        }
        return objectMapper.readValue(trimmed, new com.fasterxml.jackson.core.type.TypeReference<>() {
        });
    }

    private List<String> toStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
        }
        return result;
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
