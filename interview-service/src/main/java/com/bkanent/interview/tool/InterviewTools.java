package com.bkanent.interview.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.interview.entity.InterviewAssetEntity;
import com.bkanent.interview.entity.InterviewQuestionEntity;
import com.bkanent.interview.entity.InterviewSessionEntity;
import com.bkanent.interview.mapper.InterviewAssetMapper;
import com.bkanent.interview.mapper.InterviewQuestionMapper;
import com.bkanent.interview.mapper.InterviewSessionMapper;
import com.bkanent.interview.runtime.InterviewSessionStateMachine;
import com.bkanent.interview.service.InterviewPrepService;
import com.bkanent.interview.service.InterviewReportService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 治理面 @Tool：开台磋商、题目确认、会话启动（签发凭据+入口工件）、
 * 进度查询、导演指令、报告触发、三库检索。
 */
@Component
public class InterviewTools {

    private final InterviewPrepService prepService;
    private final InterviewSessionMapper sessionMapper;
    private final InterviewQuestionMapper questionMapper;
    private final InterviewAssetMapper assetMapper;
    private final InterviewSessionStateMachine stateMachine;
    private final InterviewReportService reportService;

    public InterviewTools(InterviewPrepService prepService,
                          InterviewSessionMapper sessionMapper,
                          InterviewQuestionMapper questionMapper,
                          InterviewAssetMapper assetMapper,
                          InterviewSessionStateMachine stateMachine,
                          InterviewReportService reportService) {
        this.prepService = prepService;
        this.sessionMapper = sessionMapper;
        this.questionMapper = questionMapper;
        this.assetMapper = assetMapper;
        this.stateMachine = stateMachine;
        this.reportService = reportService;
    }

    @Tool(description = "Open an interview case: validates the opening essentials, routes the T1-T8 outline, "
            + "creates the prep card and a DRAFT session. Scenes: STORE_MANAGER, AGENT_DEAL, SECOND_HAND_PARTY, "
            + "NEW_HOUSE_FIELD, COMMUNITY_EXPERT. Respondent roles depend on scene (e.g. OWNER, CUSTOMER for "
            + "SECOND_HAND_PARTY; FIELD_OP, MARKETING_DIRECTOR, CONSULTANT for NEW_HOUSE_FIELD).")
    public Map<String, Object> openInterviewCase(
            @ToolParam(description = "Interview scene, one of STORE_MANAGER|AGENT_DEAL|SECOND_HAND_PARTY|NEW_HOUSE_FIELD|COMMUNITY_EXPERT") String scene,
            @ToolParam(description = "Respondent role, scene-specific enum value") String respondentRole,
            @ToolParam(description = "One-sentence interview objective") String objective,
            @ToolParam(description = "Org level 1: division name, e.g. 交易中心") String divisionName,
            @ToolParam(description = "Org level 2: region name, e.g. 天河大区") String regionName,
            @ToolParam(description = "Org level 3: business district name, e.g. 珠江新城商圈") String businessDistrict,
            @ToolParam(description = "Case status: won|active|lost|churned. lost/churned auto-enables the no-success-preset question constraints") String caseStatus,
            @ToolParam(description = "Reference duration in minutes: 15/30/45/60, controls question budget only, never auto-ends the interview") Integer referenceMinutes,
            @ToolParam(description = "Creator work number (operator employee ID)") String creatorWorkNo) {
        return prepService.openCase(scene, respondentRole, caseStatus, objective,
                divisionName, regionName, businessDistrict, referenceMinutes,
                "A2A", creatorWorkNo);
    }

    @Tool(description = "Confirm candidate questions for an interview case (question confirmation system). "
            + "Only confirmed questions enter the interview; unconfirmed ones are never counted as unanswered. "
            + "Returns the confirmed count.")
    public Map<String, Object> confirmQuestions(
            @ToolParam(description = "Interview case ID from openInterviewCase") Long caseId,
            @ToolParam(description = "Question IDs selected by the initiator") List<Long> questionIds) {
        int confirmed = prepService.confirmQuestions(caseId, questionIds);
        return Map.of("caseId", caseId, "confirmedCount", confirmed);
    }

    @Tool(description = "Start the interview session: transitions QUESTIONS_CONFIRMED -> IN_PROGRESS, issues the "
            + "session ticket (only valid for this session's turn/monitor endpoints) and returns the entry "
            + "artifact (sessionId, ticket, entry path) for the front-end interview card.")
    public Map<String, Object> startInterviewSession(
            @ToolParam(description = "Interview session ID from openInterviewCase") Long sessionId,
            @ToolParam(description = "Interview mode: AI_LEAD (AI-driven) or ASSIST (interviewer-led, AI suggests)") String mode) {
        InterviewSessionEntity session = stateMachine.transition(sessionId,
                InterviewSessionStateMachine.IN_PROGRESS,
                InterviewSessionStateMachine.Actor.GOVERNANCE);
        if (mode != null && !mode.isBlank()) {
            InterviewSessionEntity update = new InterviewSessionEntity();
            update.setId(sessionId);
            update.setMode(mode);
            sessionMapper.updateById(update);
            session = sessionMapper.selectById(sessionId);
        }
        String ticket = stateMachine.issueTicket(session);

        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("sessionId", sessionId);
        artifact.put("caseId", session.getCaseId());
        artifact.put("mode", session.getMode());
        artifact.put("status", session.getStatus());
        artifact.put("ticket", ticket);
        artifact.put("entryPath", "/interviews/" + sessionId + "/turns");
        artifact.put("streamPath", "/interviews/" + sessionId + "/stream");
        return artifact;
    }

    @Tool(description = "Get interview session progress and monitor info: current question, answered/confirmed "
            + "counts, session status, closing lock. Works identically for A2A-opened and form-opened sessions.")
    public Map<String, Object> getInterviewMonitor(
            @ToolParam(description = "Interview session ID") Long sessionId) {
        InterviewSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return Map.of("error", "session not found: " + sessionId);
        }
        Long caseId = session.getCaseId();
        InterviewQuestionEntity current = prepService.currentQuestion(caseId);
        Map<String, Object> monitor = new LinkedHashMap<>();
        monitor.put("sessionId", sessionId);
        monitor.put("status", session.getStatus());
        monitor.put("mode", session.getMode());
        monitor.put("closingLocked", session.getClosingLocked());
        monitor.put("confirmedQuestions", prepService.confirmedCount(caseId));
        monitor.put("answeredQuestions", prepService.answeredCount(caseId));
        monitor.put("currentQuestion", current == null ? null : current.getContent());
        monitor.put("currentQuestionId", current == null ? null : current.getId());
        return monitor;
    }

    @Tool(description = "Send a director command to a running interview: WRAP_UP (wrap up current question), "
            + "NEXT_QUESTION (advance to next question), PINNED_QUESTION (ask a pinned question verbatim next).")
    public Map<String, Object> sendDirectorCommand(
            @ToolParam(description = "Interview session ID") Long sessionId,
            @ToolParam(description = "Command: WRAP_UP|NEXT_QUESTION|PINNED_QUESTION") String command,
            @ToolParam(description = "For PINNED_QUESTION: the exact question text to ask verbatim") String pinnedQuestion) {
        InterviewSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return Map.of("error", "session not found: " + sessionId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("command", command);
        result.put("accepted", true);
        if (pinnedQuestion != null && !pinnedQuestion.isBlank()) {
            result.put("pinnedQuestion", pinnedQuestion);
        }
        return result;
    }

    @Tool(description = "Finalize an interview session: transitions to CLOSING_LOCKED. The collection "
            + "compensator then archives the transcript with deterministic L0-L3 grading.")
    public Map<String, Object> finalizeInterview(
            @ToolParam(description = "Interview session ID") Long sessionId) {
        stateMachine.transition(sessionId, InterviewSessionStateMachine.CLOSING_LOCKED,
                InterviewSessionStateMachine.Actor.RUNTIME);
        return Map.of("sessionId", sessionId, "status", "CLOSING_LOCKED");
    }

    @Tool(description = "Trigger case card report generation as a durable task. Idempotent: same input snapshot "
            + "reuses the same task; an existing successful report is returned without re-calling the model. "
            + "Returns task id and status for polling.")
    public Map<String, Object> generateCaseReport(
            @ToolParam(description = "Interview case ID") Long caseId,
            @ToolParam(description = "Source transcript asset ID (L2/L3 archived)") Long assetId) {
        return reportService.submitCaseCardTask(caseId, assetId);
    }

    @Tool(description = "Search archived interview transcripts (L2/L3 only) and derived reports by keyword, "
            + "scene or creator work number.")
    public List<Map<String, Object>> searchAssets(
            @ToolParam(description = "Keyword to match in title/content, blank to skip") String keyword,
            @ToolParam(description = "Scene filter, e.g. SECOND_HAND_PARTY, blank to skip") String scene,
            @ToolParam(description = "Creator work number filter, blank to skip") String creatorWorkNo) {
        List<InterviewAssetEntity> assets = assetMapper.selectList(
                new LambdaQueryWrapper<InterviewAssetEntity>()
                        .in(InterviewAssetEntity::getArchiveGrade, "L2", "L3")
                        .eq(creatorWorkNo != null && !creatorWorkNo.isBlank(),
                                InterviewAssetEntity::getId, -1L)
                        .like(keyword != null && !keyword.isBlank(),
                                InterviewAssetEntity::getTitle, keyword == null ? "" : keyword)
                        .orderByDesc(InterviewAssetEntity::getId)
                        .last("LIMIT 20"));
        List<Map<String, Object>> results = new ArrayList<>();
        for (InterviewAssetEntity asset : assets) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("assetId", asset.getId());
            item.put("title", asset.getTitle());
            item.put("grade", asset.getArchiveGrade());
            item.put("source", asset.getSource());
            item.put("turnCount", asset.getTurnCount());
            item.put("wordCount", asset.getWordCount());
            results.add(item);
        }
        return results;
    }

    @Tool(description = "Re-grade an asset after metadata completion, using the same deterministic L0-L3 rules.")
    public Map<String, Object> regradeAsset(
            @ToolParam(description = "Asset ID to re-grade") Long assetId) {
        InterviewAssetEntity asset = assetMapper.selectById(assetId);
        if (asset == null) {
            return Map.of("error", "asset not found: " + assetId);
        }
        int wordCount = asset.getWordCount() == null ? 0 : asset.getWordCount();
        int turnCount = asset.getTurnCount() == null ? 0 : asset.getTurnCount();
        var grade = com.bkanent.interview.engine.ArchiveGrader.grade(wordCount,
                true, wordCount >= com.bkanent.interview.engine.ArchiveGrader.L2_TOPIC_FOCUS_WORDS,
                turnCount >= 6, turnCount >= com.bkanent.interview.engine.ArchiveGrader.L2_MIN_TURNS,
                turnCount, true);
        InterviewAssetEntity update = new InterviewAssetEntity();
        update.setId(assetId);
        update.setArchiveGrade(grade.grade());
        update.setGradeBasis(com.bkanent.interview.engine.ArchiveGrader.gradeBasisJson(grade));
        assetMapper.updateById(update);
        return Map.of("assetId", assetId, "grade", grade.grade());
    }
}
