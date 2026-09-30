package com.bkanent.interview.mcp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.common.tool.McpTool;
import com.bkanent.interview.entity.InterviewAssetEntity;
import com.bkanent.interview.entity.InterviewReportEntity;
import com.bkanent.interview.entity.InterviewReportTaskEntity;
import com.bkanent.interview.mapper.InterviewAssetMapper;
import com.bkanent.interview.mapper.InterviewReportMapper;
import com.bkanent.interview.mapper.InterviewReportTaskMapper;
import com.bkanent.interview.service.InterviewCollectionService;
import com.bkanent.interview.service.InterviewReportService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 能力面：公开只读资产工具。
 *
 * <p>隔离边界（用户决策：公开资产只读）：可见范围限定 L2/L3 已归档逐字稿
 * 及其衍生报告；管理动作（删除/改名/重评级/题目确认/会话干预）零暴露；
 * 唯一写路径为 submit_transcript（只新增资产，不改既有资产）。</p>
 */
@Component
public class InterviewMcpTools implements McpTool {

    private final InterviewAssetMapper assetMapper;
    private final InterviewReportMapper reportMapper;
    private final InterviewReportTaskMapper reportTaskMapper;
    private final InterviewReportService reportService;

    public InterviewMcpTools(InterviewAssetMapper assetMapper,
                             InterviewReportMapper reportMapper,
                             InterviewReportTaskMapper reportTaskMapper,
                             InterviewReportService reportService) {
        this.assetMapper = assetMapper;
        this.reportMapper = reportMapper;
        this.reportTaskMapper = reportTaskMapper;
        this.reportService = reportService;
    }

    @Tool(description = "Search archived interview transcripts (L2/L3 only). Public read-only view; "
            + "L0/L1 assets are never visible via MCP.")
    public List<Map<String, Object>> search_transcripts(
            @ToolParam(description = "Keyword to match in title, blank to list recent") String keyword) {
        List<InterviewAssetEntity> assets = assetMapper.selectList(
                new LambdaQueryWrapper<InterviewAssetEntity>()
                        .in(InterviewAssetEntity::getArchiveGrade, "L2", "L3")
                        .like(keyword != null && !keyword.isBlank(),
                                InterviewAssetEntity::getTitle, keyword == null ? "" : keyword)
                        .orderByDesc(InterviewAssetEntity::getId)
                        .last("LIMIT 20"));
        List<Map<String, Object>> results = new ArrayList<>();
        for (InterviewAssetEntity asset : assets) {
            results.add(assetView(asset, false));
        }
        return results;
    }

    @Tool(description = "Get a case card report by report ID or by its source transcript asset ID. "
            + "Returns the structured report with evidence verification status.")
    public Map<String, Object> get_case_report(
            @ToolParam(description = "Report ID, or blank when querying by assetId") Long reportId,
            @ToolParam(description = "Source transcript asset ID, used when reportId is blank") Long assetId) {
        InterviewReportEntity report = reportId != null
                ? reportMapper.selectById(reportId)
                : (assetId != null ? reportMapper.selectOne(new LambdaQueryWrapper<InterviewReportEntity>()
                        .eq(InterviewReportEntity::getAssetId, assetId)
                        .orderByDesc(InterviewReportEntity::getId)
                        .last("LIMIT 1")) : null);
        if (report == null) {
            return Map.of("error", "report not found");
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("reportId", report.getId());
        view.put("caseId", report.getCaseId());
        view.put("assetId", report.getAssetId());
        view.put("reportType", report.getReportType());
        view.put("score", report.getScore());
        view.put("replicabilityLevel", report.getReplicabilityLevel());
        view.put("report", report.getReportJson());
        view.put("evidenceVerification", report.getEvidenceVerification());
        view.put("missingItems", report.getMissingItems());
        return view;
    }

    @Tool(description = "Submit an external transcript (markdown/plain text) for archiving. "
            + "Deterministic L0-L3 grading runs automatically (no model). Returns the new asset ID and grade. "
            + "This is the only write path on the MCP surface; existing assets are never modified.")
    public Map<String, Object> submit_transcript(
            @ToolParam(description = "Transcript title") String title,
            @ToolParam(description = "Transcript full text (markdown or plain)") String content,
            @ToolParam(description = "Case ID to attach, optional") Long caseId) {
        String sanitized = content == null ? "" : content;
        int wordCount = sanitized.replaceAll("\\s+", "").length();
        int estimatedTurns = countTurns(sanitized);
        var grade = com.bkanent.interview.engine.ArchiveGrader.grade(wordCount, true,
                wordCount >= com.bkanent.interview.engine.ArchiveGrader.L2_TOPIC_FOCUS_WORDS,
                estimatedTurns >= 6, estimatedTurns >= com.bkanent.interview.engine.ArchiveGrader.L2_MIN_TURNS,
                estimatedTurns, true);

        InterviewAssetEntity asset = new InterviewAssetEntity();
        asset.setCaseId(caseId == null ? 0L : caseId);
        asset.setSource("UPLOAD");
        asset.setTitle(title == null || title.isBlank() ? "外部提交逐字稿" : title);
        asset.setContent(sanitized);
        asset.setSnapshotHash(sha256(sanitized));
        asset.setArchiveGrade(grade.grade());
        asset.setGradeBasis(com.bkanent.interview.engine.ArchiveGrader.gradeBasisJson(grade));
        asset.setTurnCount(estimatedTurns);
        asset.setWordCount(wordCount);
        assetMapper.insert(asset);
        return Map.of("assetId", asset.getId(), "grade", grade.grade(),
                "wordCount", wordCount, "turnCount", estimatedTurns);
    }

    @Tool(description = "Run deterministic L0-L3 grading on a transcript text without archiving it. "
            + "Pure rule-based check: body length, subject, topic focus, module closure, evidence, turn count.")
    public Map<String, Object> grade_transcript(
            @ToolParam(description = "Transcript text to grade") String content) {
        String sanitized = content == null ? "" : content;
        int wordCount = sanitized.replaceAll("\\s+", "").length();
        int estimatedTurns = countTurns(sanitized);
        var grade = com.bkanent.interview.engine.ArchiveGrader.grade(wordCount, true,
                wordCount >= com.bkanent.interview.engine.ArchiveGrader.L2_TOPIC_FOCUS_WORDS,
                estimatedTurns >= 6, estimatedTurns >= com.bkanent.interview.engine.ArchiveGrader.L2_MIN_TURNS,
                estimatedTurns, true);
        return Map.of("grade", grade.grade(),
                "gradeBasis", com.bkanent.interview.engine.ArchiveGrader.gradeBasisJson(grade));
    }

    @Tool(description = "Search case card reports across cases and tasks by keyword. Public read-only "
            + "view matching the transcript search boundary; returns report summaries (score, replicability "
            + "level, one-liner). Use get_case_report for a full report.")
    public Map<String, Object> search_reports(
            @ToolParam(description = "Keyword to match in report text, required") String keyword,
            @ToolParam(description = "Page number, 1-based") Integer page,
            @ToolParam(description = "Page size, max 20 on the public surface") Integer pageSize) {
        return reportService.searchReports(keyword, null,
                page == null ? 1 : page, pageSize == null ? 10 : Math.min(pageSize, 20));
    }

    @Tool(description = "Submit an asynchronous case card report generation task for an L2/L3 transcript. "
            + "Idempotent: same input reuses the same task. Returns taskId and status for polling via this tool.")
    public Map<String, Object> generate_case_report(
            @ToolParam(description = "Interview case ID") Long caseId,
            @ToolParam(description = "Source transcript asset ID (L2/L3 archived)") Long assetId) {
        Map<String, Object> submitted = reportService.submitCaseCardTask(caseId, assetId);
        Long taskId = (Long) submitted.get("taskId");
        if (taskId != null) {
            InterviewReportTaskEntity task = reportTaskMapper.selectById(taskId);
            if (task != null && "SUCCEEDED".equals(task.getStatus()) && task.getReportId() != null) {
                submitted.put("report", get_case_report(task.getReportId(), null));
            }
        }
        return submitted;
    }

    private Map<String, Object> assetView(InterviewAssetEntity asset, boolean withContent) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("assetId", asset.getId());
        view.put("title", asset.getTitle());
        view.put("grade", asset.getArchiveGrade());
        view.put("source", asset.getSource());
        view.put("turnCount", asset.getTurnCount());
        view.put("wordCount", asset.getWordCount());
        if (withContent) {
            view.put("content", asset.getContent());
        }
        return view;
    }

    private int countTurns(String content) {
        int count = 0;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("受访者") || trimmed.startsWith("访谈员")
                    || trimmed.startsWith("问：") || trimmed.startsWith("答：")) {
                count++;
            }
        }
        return Math.max(count, content.split("\\n\\s*\\n").length);
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("sha256 failed", e);
        }
    }
}
