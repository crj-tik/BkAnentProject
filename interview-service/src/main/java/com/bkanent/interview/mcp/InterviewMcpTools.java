package com.bkanent.interview.mcp;

import com.bkanent.common.tool.McpTool;
import com.bkanent.interview.entity.InterviewAssetEntity;
import com.bkanent.interview.entity.InterviewReportTaskEntity;
import com.bkanent.interview.mapper.InterviewAssetMapper;
import com.bkanent.interview.mapper.InterviewReportTaskMapper;
import com.bkanent.interview.service.InterviewAssetService;
import com.bkanent.interview.service.InterviewReportService;
import com.bkanent.interview.engine.Sanitizer;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 能力面：公开只读资产工具。
 *
 * <p>公开读取限定为已发布的L2/L3逐字稿与有效来源报告；业务管理动作不暴露。
 * 私有写操作仅包括授权后的逐字稿新增与报告生成，不修改既有资产。</p>
 */
@Component
public class InterviewMcpTools implements McpTool {

    private final InterviewAssetMapper assetMapper;
    private final InterviewReportTaskMapper reportTaskMapper;
    private final InterviewReportService reportService;
    private final InterviewAssetService assetService;

    public InterviewMcpTools(InterviewAssetMapper assetMapper,
                             InterviewReportTaskMapper reportTaskMapper,
                             InterviewReportService reportService,
                             InterviewAssetService assetService) {
        this.assetMapper = assetMapper;
        this.reportTaskMapper = reportTaskMapper;
        this.reportService = reportService;
        this.assetService = assetService;
    }

    @Tool(description = "Search archived interview transcripts (L2/L3 only). Public read-only view; "
            + "L0/L1 assets are never visible via MCP.")
    public List<Map<String, Object>> search_transcripts(
            @ToolParam(description = "Keyword to match in title, blank to list recent") String keyword) {
        String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.length() > 200) return List.of();
        List<InterviewAssetEntity> assets = assetMapper.selectPublicAssets(normalized,
                Sanitizer.VERSION, 20);
        List<Map<String, Object>> results = new ArrayList<>();
        for (InterviewAssetEntity asset : assets) {
            if (!assetService.isPubliclyEligible(asset)) continue;
            results.add(assetView(asset, false));
        }
        return results;
    }

    @Tool(description = "Get a case card report by report ID or by its source transcript asset ID. "
            + "Returns only a current report whose source transcript is explicitly published and eligible for public reading.")
    public Map<String, Object> get_case_report(
            @ToolParam(description = "Report ID, or blank when querying by assetId") Long reportId,
            @ToolParam(description = "Source transcript asset ID, used when reportId is blank") Long assetId) {
        Map<String, Object> report = reportService.getPublicReport(reportId, assetId);
        return report == null ? Map.of("error", "report not found") : report;
    }

    @Tool(description = "Submit an external transcript (markdown/plain text) for archiving. "
            + "Deterministic L0-L3 grading runs automatically (no model). Returns the new asset ID and grade. "
            + "Requires the exact interview.asset.submit permission and an authorized case ID. "
            + "The new asset remains private until explicitly published; existing assets are never modified.")
    public Map<String, Object> submit_transcript(
            @ToolParam(description = "Transcript title") String title,
            @ToolParam(description = "Transcript full text (markdown or plain)") String content,
            @ToolParam(description = "Required case ID to attach the new private transcript") Long caseId) {
        InterviewAssetEntity asset = assetService.submitTranscript(caseId, title, content);
        return Map.of("assetId", asset.getId(), "grade", asset.getArchiveGrade(),
                "visibility", asset.getVisibility(), "wordCount", asset.getWordCount(),
                "turnCount", asset.getTurnCount());
    }

    @Tool(description = "Preview deterministic transcript quality checks without storing or publishing the text.")
    public Map<String, Object> grade_transcript(
            @ToolParam(description = "Transcript text to grade") String content) {
        String sanitized = Sanitizer.sanitize(content == null ? "" : content).sanitizedText();
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
        return reportService.searchPublicReports(keyword,
                page == null ? 1 : page, pageSize == null ? 10 : Math.min(pageSize, 20));
    }

    @Tool(description = "Submit an authorized report task for a sanitized transcript attached to the same case. "
            + "Idempotent: the same source snapshot reuses the same task. Returns taskId and status; public report "
            + "reads still require the source transcript to be explicitly published and eligible.")
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

}
