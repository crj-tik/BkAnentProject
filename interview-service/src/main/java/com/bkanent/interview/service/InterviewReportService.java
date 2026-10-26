package com.bkanent.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.interview.config.InterviewReportProperties;
import com.bkanent.interview.entity.InterviewAssetEntity;
import com.bkanent.interview.entity.InterviewCaseEntity;
import com.bkanent.interview.entity.InterviewReportEntity;
import com.bkanent.interview.entity.InterviewReportTaskEntity;
import com.bkanent.interview.engine.EvidenceVerifier;
import com.bkanent.interview.engine.Sanitizer;
import com.bkanent.interview.mapper.InterviewAssetMapper;
import com.bkanent.interview.mapper.InterviewCaseMapper;
import com.bkanent.interview.mapper.InterviewReportMapper;
import com.bkanent.interview.mapper.InterviewReportTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
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
    public static final String TEMPLATE_VERSION = "case-card-v1";

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
    private final InterviewCaseMapper caseMapper;
    private final ChatModel chatModel;
    private final InterviewReportProperties properties;
    private final ObjectMapper objectMapper;
    private final InterviewAssetService assetService;
    private final InterviewAuthorizationService authorizationService;
    private final InterviewReportTaskCommitter taskCommitter;

    @Autowired
    public InterviewReportService(InterviewReportTaskMapper taskMapper,
                                  InterviewReportMapper reportMapper,
                                  InterviewAssetMapper assetMapper,
                                  InterviewCaseMapper caseMapper,
                                  ChatModel chatModel,
                                  InterviewReportProperties properties,
                                  ObjectMapper objectMapper,
                                  InterviewAssetService assetService,
                                  InterviewAuthorizationService authorizationService,
                                  InterviewReportTaskCommitter taskCommitter) {
        this.taskMapper = taskMapper;
        this.reportMapper = reportMapper;
        this.assetMapper = assetMapper;
        this.caseMapper = caseMapper;
        this.chatModel = chatModel;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.assetService = assetService;
        this.authorizationService = authorizationService;
        this.taskCommitter = taskCommitter;
    }

    /**
     * 报告任务列表（分页）：按创建人（经 case 表解析）与状态过滤，按提交时间倒序。
     * 供任务中心页拉取；创建人过滤为空时返回全部任务（平台尚无组织鉴权，公开只读口径）。
     */
    public Map<String, Object> listCaseCardTasks(String creatorWorkNo, String status,
                                                 int page, int pageSize) {
        return listCaseCardTasks(creatorWorkNo, status, page, pageSize, null);
    }

    /** Versioned callers pass the participant-derived case scope before pagination. */
    public Map<String, Object> listCaseCardTasks(String creatorWorkNo, String status,
                                                 int page, int pageSize, List<Long> visibleCaseIds) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        if (visibleCaseIds != null && visibleCaseIds.isEmpty()) {
            return Map.of("tasks", List.of(), "total", 0L, "page", safePage, "pageSize", safeSize);
        }

        // 创建人在 case 表：先解析创建人的 caseId 集合（IN 过滤；空集合即无任务）
        List<Long> caseIdFilter = null;
        if (creatorWorkNo != null && !creatorWorkNo.isBlank()) {
            caseIdFilter = caseMapper.selectList(new LambdaQueryWrapper<InterviewCaseEntity>()
                            .eq(InterviewCaseEntity::getCreatorWorkNo, creatorWorkNo))
                    .stream().map(InterviewCaseEntity::getId).toList();
            if (caseIdFilter.isEmpty()) {
                return Map.of("tasks", List.of(), "total", 0L, "page", safePage, "pageSize", safeSize);
            }
        }
        caseIdFilter = intersect(caseIdFilter, visibleCaseIds);
        if (caseIdFilter != null && caseIdFilter.isEmpty()) {
            return Map.of("tasks", List.of(), "total", 0L, "page", safePage, "pageSize", safeSize);
        }

        LambdaQueryWrapper<InterviewReportTaskEntity> wrapper = new LambdaQueryWrapper<InterviewReportTaskEntity>()
                .eq(status != null && !status.isBlank(),
                        InterviewReportTaskEntity::getStatus, status == null ? "" : status)
                .orderByDesc(InterviewReportTaskEntity::getId);
        if (caseIdFilter != null) {
            wrapper.in(InterviewReportTaskEntity::getCaseId, caseIdFilter);
        }

        long total = taskMapper.selectCount(wrapper);
        wrapper.last("LIMIT " + safeSize + " OFFSET " + (safePage - 1) * safeSize);
        List<InterviewReportTaskEntity> tasks = taskMapper.selectList(wrapper);

        List<Map<String, Object>> items = new java.util.ArrayList<>(tasks.size());
        for (InterviewReportTaskEntity task : tasks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", task.getId());
            item.put("caseId", task.getCaseId());
            item.put("assetId", task.getAssetId());
            item.put("status", task.getStatus());
            item.put("reportId", task.getReportId());
            item.put("errorClass", Sanitizer.redactCredentials(task.getErrorClass()));
            item.put("errorMessage", Sanitizer.redactCredentials(task.getErrorMessage()));
            item.put("retries", task.getRetries());
            item.put("createdAt", task.getCreatedAt() == null ? null : task.getCreatedAt().toString());
            item.put("updatedAt", task.getUpdatedAt() == null ? null : task.getUpdatedAt().toString());
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tasks", items);
        result.put("total", total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        return result;
    }

    /**
     * 跨任务报告搜索（分页）：关键词匹配报告正文（report_json 结构化文本），
     * 可叠加创建人过滤；报告维度、提交时间倒序。跨 case 跨任务，供报告中心检索。
     */
    public Map<String, Object> searchReports(String keyword, String creatorWorkNo,
                                             int page, int pageSize) {
        return searchReports(keyword, creatorWorkNo, page, pageSize, null);
    }

    /** Versioned callers pass the participant-derived case scope before pagination. */
    public Map<String, Object> searchReports(String keyword, String creatorWorkNo,
                                             int page, int pageSize, List<Long> visibleCaseIds) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        if (visibleCaseIds != null && visibleCaseIds.isEmpty()) {
            return Map.of("reports", List.of(), "total", 0L, "page", safePage, "pageSize", safeSize);
        }
        if (keyword == null || keyword.isBlank()) {
            return Map.of("reports", List.of(), "total", 0L, "page", safePage, "pageSize", safeSize);
        }

        List<Long> caseIdFilter = null;
        if (creatorWorkNo != null && !creatorWorkNo.isBlank()) {
            caseIdFilter = caseMapper.selectList(new LambdaQueryWrapper<InterviewCaseEntity>()
                            .eq(InterviewCaseEntity::getCreatorWorkNo, creatorWorkNo))
                    .stream().map(InterviewCaseEntity::getId).toList();
            if (caseIdFilter.isEmpty()) {
                return Map.of("reports", List.of(), "total", 0L, "page", safePage, "pageSize", safeSize);
            }
        }
        caseIdFilter = intersect(caseIdFilter, visibleCaseIds);
        if (caseIdFilter != null && caseIdFilter.isEmpty()) {
            return Map.of("reports", List.of(), "total", 0L, "page", safePage, "pageSize", safeSize);
        }

        long total = reportMapper.countCurrentReports(keyword.trim(), caseIdFilter);
        List<InterviewReportEntity> reports = reportMapper.selectCurrentReports(keyword.trim(), caseIdFilter,
                safeSize, (safePage - 1) * safeSize);

        List<Map<String, Object>> items = new ArrayList<>(reports.size());
        for (InterviewReportEntity report : reports) {
            if (isCurrentReport(report)) items.add(reportView(report));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reports", items);
        result.put("total", total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        return result;
    }

    /** Public report projection: SQL and domain source facts must both still qualify. */
    public Map<String, Object> getPublicReport(Long reportId, Long assetId) {
        if ((reportId == null) == (assetId == null)) return null;
        InterviewReportEntity report = reportId != null
                ? reportMapper.findPublicReportById(reportId, com.bkanent.interview.engine.Sanitizer.VERSION)
                : reportMapper.findPublicReportByAssetId(assetId, com.bkanent.interview.engine.Sanitizer.VERSION);
        if (report == null || !isCurrentReport(report) || !isPublicSourceCurrent(report)) return null;
        Map<String, Object> view = reportView(report);
        view.put("report", report.getReportJson());
        view.put("evidenceVerification", report.getEvidenceVerification());
        view.put("missingItems", report.getMissingItems());
        return view;
    }

    /** Public MCP report search, filtered by publication and source validity before pagination. */
    public Map<String, Object> searchPublicReports(String keyword, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), 20);
        if (keyword == null || keyword.isBlank() || keyword.length() > 200) {
            return Map.of("reports", List.of(), "total", 0L, "page", safePage, "pageSize", safeSize);
        }
        String normalized = keyword.trim();
        long total = reportMapper.countPublicReports(normalized,
                com.bkanent.interview.engine.Sanitizer.VERSION);
        List<InterviewReportEntity> reports = reportMapper.selectPublicReports(normalized,
                com.bkanent.interview.engine.Sanitizer.VERSION, safeSize, (safePage - 1) * safeSize);
        List<Map<String, Object>> items = new ArrayList<>(reports.size());
        for (InterviewReportEntity report : reports) {
            if (isCurrentReport(report) && isPublicSourceCurrent(report)) items.add(reportView(report));
        }
        return Map.of("reports", items, "total", total, "page", safePage, "pageSize", safeSize);
    }

    /** 报告摘要视图：不回传全文（report_json 可能很长），前端经任务详情端点按需取全文。 */
    private Map<String, Object> reportView(InterviewReportEntity report) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("reportId", report.getId());
        item.put("caseId", report.getCaseId());
        item.put("sessionId", report.getSessionId());
        item.put("assetId", report.getAssetId());
        item.put("reportType", report.getReportType());
        item.put("score", report.getScore());
        item.put("replicabilityLevel", report.getReplicabilityLevel());
        item.put("oneLiner", extractOneLiner(report.getReportJson()));
        item.put("createdAt", report.getCreatedAt() == null ? null : report.getCreatedAt().toString());
        return item;
    }

    private boolean isCurrentReport(InterviewReportEntity report) {
        if (report == null || report.getId() == null || report.getTaskId() == null
                || report.getAssetId() == null || report.getCaseId() == null
                || report.getSourceContentHash() == null || report.getSourceRevision() == null
                || report.getTemplateVersion() == null || Sanitizer.containsCredentialShape(report.getReportJson())
                || Sanitizer.containsCredentialShape(report.getEvidenceVerification())
                || Sanitizer.containsCredentialShape(report.getMissingItems())) return false;
        InterviewReportTaskEntity task = taskMapper.selectById(report.getTaskId());
        InterviewAssetEntity source = assetMapper.selectById(report.getAssetId());
        return task != null && "SUCCEEDED".equals(task.getStatus())
                && source != null
                && report.getTaskId().equals(task.getId())
                && report.getId().equals(task.getReportId())
                && report.getCaseId().equals(task.getCaseId())
                && report.getAssetId().equals(task.getAssetId())
                && report.getInputSnapshotHash() != null
                && report.getInputSnapshotHash().equals(task.getInputSnapshotHash())
                && report.getSourceContentHash().equals(task.getSourceContentHash())
                && report.getSourceRevision().equals(task.getSourceRevision())
                && task.getSourceSanitizationRevision() != null
                && task.getSourceSanitizationRevision().equals(source.getSanitizationRevision())
                && report.getTemplateVersion().equals(task.getTemplateVersion())
                && report.getCaseId().equals(source.getCaseId())
                && report.getSourceContentHash().equals(source.getContentHash())
                && report.getSourceRevision().equals(source.getContentRevision())
                && report.getTemplateVersion().equals(task.getTemplateVersion())
                && assetService.isUsableReportSource(source);
    }

    private boolean isPublicSourceCurrent(InterviewReportEntity report) {
        InterviewAssetEntity source = report == null ? null : assetMapper.selectById(report.getAssetId());
        return source != null && assetService.isPubliclyEligible(source)
                && report.getCaseId().equals(source.getCaseId())
                && report.getSourceContentHash().equals(source.getContentHash())
                && report.getSourceRevision().equals(source.getContentRevision());
    }

    /** 从 report_json 抽 oneLiner 作结果行摘要；解析失败时返回 null（前端回退显示 ID）。 */
    private String extractOneLiner(String reportJson) {
        if (reportJson == null || reportJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(reportJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            if (parsed.get("coreFinding") instanceof Map<?, ?> coreFinding
                    && coreFinding.get("oneLiner") != null) {
                return String.valueOf(coreFinding.get("oneLiner"));
            }
        } catch (Exception exception) {
            return null;
        }
        return null;
    }

    /**
     * 任务详情（含已生成报告的结构化结论与核验状态）。
     */
    public Map<String, Object> getTaskDetail(Long taskId) {
        return getTaskDetail(taskId, null);
    }

    /** Hides foreign task IDs exactly like missing IDs. */
    public Map<String, Object> getTaskDetail(Long taskId, List<Long> visibleCaseIds) {
        InterviewReportTaskEntity task = taskMapper.selectById(taskId);
        if (task == null || visibleCaseIds != null && !visibleCaseIds.contains(task.getCaseId())) {
            return Map.of("error", "task not found: " + taskId);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("taskId", task.getId());
        view.put("caseId", task.getCaseId());
        view.put("assetId", task.getAssetId());
        view.put("status", task.getStatus());
        view.put("errorClass", Sanitizer.redactCredentials(task.getErrorClass()));
        view.put("errorMessage", Sanitizer.redactCredentials(task.getErrorMessage()));
        view.put("retries", task.getRetries());
        view.put("reportId", task.getReportId());
        view.put("createdAt", task.getCreatedAt() == null ? null : task.getCreatedAt().toString());
        if (task.getReportId() != null) {
            InterviewReportEntity report = reportMapper.selectById(task.getReportId());
            if (report != null && task.getId().equals(report.getTaskId()) && isCurrentReport(report)) {
                view.put("report", Map.of(
                        "reportId", report.getId(),
                        "reportType", report.getReportType(),
                        "score", report.getScore(),
                        "replicabilityLevel", report.getReplicabilityLevel(),
                        "reportJson", report.getReportJson(),
                        "evidenceVerification", report.getEvidenceVerification(),
                        "missingItems", report.getMissingItems()));
            }
        }
        return view;
    }

    private static List<Long> intersect(List<Long> first, List<Long> second) {
        if (second == null) return first;
        if (first == null) return List.copyOf(new java.util.LinkedHashSet<>(second));
        java.util.Set<Long> allowed = new java.util.HashSet<>(second);
        return first.stream().filter(allowed::contains).distinct().toList();
    }

    /**
     * 提交案例卡任务：输入快照哈希幂等——相同输入复用同一任务；
     * 已成功且有报告直接返回（不再调模型）。
     */
    public Map<String, Object> submitCaseCardTask(Long caseId, Long assetId) {
        authorizationService.requireOperation(InterviewAuthorizationService.REPORT_GENERATE);
        InterviewAssetEntity asset = assetService.requireReportSource(caseId, assetId);
        if (asset.getSessionId() != null) {
            authorizationService.requireInitiator(asset.getSessionId(), InterviewAuthorizationService.REPORT_GENERATE);
        } else {
            authorizationService.requireInitiatorForCase(caseId, InterviewAuthorizationService.REPORT_GENERATE);
        }
        InterviewAuthorizationService.ActorFacts actor = authorizationService.currentActorFacts();
        String snapshotHash = sha256(String.join(":", "CASE_CARD", TEMPLATE_VERSION,
                caseId.toString(), assetId.toString(), asset.getContentHash(),
                asset.getContentRevision().toString(), asset.getSanitizationRevision().toString(),
                String.valueOf(asset.getArchiveGrade())));

        InterviewReportTaskEntity existing = taskMapper.selectOne(
                new LambdaQueryWrapper<InterviewReportTaskEntity>()
                        .eq(InterviewReportTaskEntity::getIdempotencyKey, snapshotHash));
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
        task.setIdempotencyKey(snapshotHash);
        task.setLeaseEpoch(0);
        task.setRetries(0);
        task.setMaxRetries(properties.getMaxRetries());
        task.setSessionId(asset.getSessionId());
        task.setSourceContentHash(asset.getContentHash());
        task.setSourceRevision(asset.getContentRevision());
        task.setSourceSanitizationRevision(asset.getSanitizationRevision());
        task.setTemplateVersion(TEMPLATE_VERSION);
        task.setRequestedByUserId(actor.userId());
        task.setPermissionRevision(actor.permissionRevision());
        task.setCredentialEpoch(actor.credentialEpoch());
        task.setAuthorizationExpiresAt(LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(actor.expiresAtEpochSecond()), java.time.ZoneId.systemDefault()));
        task.setBusinessWide(actor.businessWide());
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException raced) {
            InterviewReportTaskEntity winner = taskMapper.selectOne(new LambdaQueryWrapper<InterviewReportTaskEntity>()
                    .eq(InterviewReportTaskEntity::getIdempotencyKey, snapshotHash));
            if (winner == null) throw raced;
            return taskView(winner, "reused");
        }
        return taskView(task, "created");
    }

    Map<String, Object> taskView(InterviewReportTaskEntity task, String disposition) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("taskId", task.getId());
        view.put("status", task.getStatus());
        view.put("disposition", disposition);
        view.put("reportId", task.getReportId());
        view.put("errorClass", Sanitizer.redactCredentials(task.getErrorClass()));
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
        LocalDateTime now = LocalDateTime.now();
        String owner = UUID.randomUUID().toString();
        int epoch = (task.getLeaseEpoch() == null ? 0 : task.getLeaseEpoch()) + 1;
        int rows = taskMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InterviewReportTaskEntity>()
                .eq(InterviewReportTaskEntity::getId, task.getId())
                .eq(InterviewReportTaskEntity::getLeaseEpoch, task.getLeaseEpoch() == null ? 0 : task.getLeaseEpoch())
                .in(InterviewReportTaskEntity::getStatus, "PENDING", "RETRYABLE", "RUNNING")
                .and(w -> w.isNull(InterviewReportTaskEntity::getLeaseExpiresAt)
                        .or().lt(InterviewReportTaskEntity::getLeaseExpiresAt, now))
                .set(InterviewReportTaskEntity::getStatus, "RUNNING")
                .set(InterviewReportTaskEntity::getLeaseOwner, owner)
                .set(InterviewReportTaskEntity::getLeaseExpiresAt, now.plusMinutes(properties.getLeaseMinutes()))
                .set(InterviewReportTaskEntity::getLeaseEpoch, epoch));
        if (rows == 0) {
            return; // 其他执行器抢到
        }
        InterviewReportTaskCommitter.Claim claim = new InterviewReportTaskCommitter.Claim(task.getId(), owner, epoch);
        try {
            execute(claim);
        } catch (Exception e) {
            log.warn("Report task {} failed in {}", task.getId(), e.getClass().getSimpleName());
            taskCommitter.fail(claim, e.getClass().getSimpleName());
        }
    }

    /** Executes the accepted sanitized snapshot outside a transaction; the final write is fenced separately. */
    private void execute(InterviewReportTaskCommitter.Claim claim) throws Exception {
        InterviewReportTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<InterviewReportTaskEntity>()
                .eq(InterviewReportTaskEntity::getId, claim.taskId())
                .eq(InterviewReportTaskEntity::getLeaseOwner, claim.owner())
                .eq(InterviewReportTaskEntity::getLeaseEpoch, claim.epoch())
                .eq(InterviewReportTaskEntity::getStatus, "RUNNING")
                .gt(InterviewReportTaskEntity::getLeaseExpiresAt, LocalDateTime.now())
                .last("LIMIT 1"));
        if (task == null) return;
        InterviewAssetEntity asset = assetMapper.selectById(task.getAssetId());
        if (!matchesAcceptedSource(task, asset) || !assetService.isUsableReportSource(asset)) {
            taskCommitter.block(claim, "source_snapshot_changed");
            return;
        }

        // AI 只做抽取归纳
        String llmResponse = chatModel.call(new Prompt(List.of(
                new SystemMessage(CASE_CARD_SYSTEM_PROMPT),
                new UserMessage(asset.getContent())))).getResult().getOutput().getText();
        if (Sanitizer.containsCredentialShape(llmResponse)) {
            taskCommitter.block(claim, "credential_shaped_report_output");
            return;
        }
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
        report.setSourceContentHash(task.getSourceContentHash());
        report.setSourceRevision(task.getSourceRevision());
        report.setTemplateVersion(task.getTemplateVersion());
        if (taskCommitter.complete(claim, report)) {
            log.info("Report task {} succeeded with score={} replicability={}",
                    task.getId(), score, replicability);
        }
    }

    private static boolean matchesAcceptedSource(InterviewReportTaskEntity task, InterviewAssetEntity source) {
        return source != null && task.getCaseId() != null && task.getCaseId().equals(source.getCaseId())
                && java.util.Objects.equals(task.getSessionId(), source.getSessionId())
                && task.getSourceContentHash() != null && task.getSourceContentHash().equals(source.getContentHash())
                && task.getSourceRevision() != null && task.getSourceRevision().equals(source.getContentRevision())
                && task.getSourceSanitizationRevision() != null
                && task.getSourceSanitizationRevision().equals(source.getSanitizationRevision())
                && task.getTemplateVersion() != null && TEMPLATE_VERSION.equals(task.getTemplateVersion())
                && task.getIdempotencyKey() != null
                && task.getInputSnapshotHash() != null && task.getInputSnapshotHash().equals(task.getIdempotencyKey());
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
