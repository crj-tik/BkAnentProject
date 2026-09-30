package com.bkanent.interview.runtime;

import com.bkanent.interview.entity.InterviewDirectorCommandEntity;
import com.bkanent.interview.entity.InterviewQuestionEntity;
import com.bkanent.interview.entity.InterviewSessionEntity;
import com.bkanent.interview.mapper.InterviewDirectorCommandMapper;
import com.bkanent.interview.mapper.InterviewSessionMapper;
import com.bkanent.interview.service.InterviewPrepService;
import com.bkanent.common.model.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 运行面 REST/SSE：前端经 gateway `/interviews/**` 直连，不经过 Supervisor。
 * 凭据只放行本场会话的话轮与监播。
 *
 * <p>双入口之一：表单式开台（POST /interviews）与 A2A 对话式开台共用
 * 同一领域服务与同一组元数据字段（一库三读法）。</p>
 */
@RestController
@RequestMapping("/interviews")
public class InterviewController {

    /** SSE 终态暂存时长（断线续取窗口，对齐 supervisor_stream_events 惯例）。 */
    private static final Duration TERMINAL_TTL = Duration.ofMinutes(15);

    private final InterviewRuntimeService runtimeService;
    private final InterviewSessionStateMachine stateMachine;
    private final InterviewSessionMapper sessionMapper;
    private final InterviewPrepService prepService;
    private final InterviewDirectorCommandMapper directorCommandMapper;

    /** 断线续取：reqId → 终态响应（含过期时间，惰性清理）。 */
    private final ConcurrentMap<String, TerminalReply> terminalReplies = new ConcurrentHashMap<>();

    public InterviewController(InterviewRuntimeService runtimeService,
                               InterviewSessionStateMachine stateMachine,
                               InterviewSessionMapper sessionMapper,
                               InterviewPrepService prepService,
                               InterviewDirectorCommandMapper directorCommandMapper) {
        this.runtimeService = runtimeService;
        this.stateMachine = stateMachine;
        this.sessionMapper = sessionMapper;
        this.prepService = prepService;
        this.directorCommandMapper = directorCommandMapper;
    }

    /** 话轮请求体。 */
    public record TurnRequest(String content, String idempotencyKey) {
    }

    /** 表单式开台请求体（与 A2A structuredContext 对齐的元数据字段）。 */
    public record OpenCaseRequest(
            String scene,
            String respondentRole,
            String objective,
            String divisionName,
            String regionName,
            String businessDistrict,
            String caseStatus,
            Integer referenceMinutes,
            String creatorWorkNo) {
    }

    /** 候选题提交体。 */
    public record QuestionDraftItem(String content, String focusLabel, String riskHint, Boolean core) {
    }

    public record CreateQuestionsRequest(List<QuestionDraftItem> questions) {
    }

    public record ConfirmQuestionsRequest(List<Long> questionIds) {
    }

    /** 导演指令直连请求体。 */
    public record DirectorCommandRequest(String command, String pinnedQuestion) {
    }

    /** SSE 终态暂存条目。 */
    private record TerminalReply(String reply, boolean closed, Instant expiresAt) {
    }

    // ---------- 双入口之一：表单式开台（与 A2A 共用 InterviewPrepService） ----------

    /**
     * 表单式开台：场景校验、T1-T8 路由、DRAFT 会话。
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> openCase(@RequestBody OpenCaseRequest request) {
        if (request.creatorWorkNo() == null || request.creatorWorkNo().isBlank()) {
            return ApiResponse.fail("INTERVIEW_CREATOR_REQUIRED", "创建人工号必填");
        }
        try {
            Map<String, Object> opened = prepService.openCase(
                    request.scene(), request.respondentRole(), request.caseStatus(), request.objective(),
                    request.divisionName(), request.regionName(), request.businessDistrict(),
                    request.referenceMinutes(), "FORM", request.creatorWorkNo());
            return ApiResponse.ok(opened);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail("INTERVIEW_OPEN_INVALID", e.getMessage());
        }
    }

    /**
     * 表单入口提交候选题（AI 出题替代路径：发起人自拟或粘贴提纲）。
     */
    @PostMapping("/{sessionId}/questions")
    public ApiResponse<Map<String, Object>> createQuestions(
            @PathVariable("sessionId") Long sessionId,
            @RequestHeader(value = "x-ticket", required = false) String ticket,
            @RequestBody CreateQuestionsRequest request) {
        if (!stateMachine.validateTicket(sessionId, ticket)) {
            return ApiResponse.fail("INTERVIEW_INVALID_TICKET", "访谈入口凭据无效或已过期");
        }
        if (request.questions() == null || request.questions().isEmpty()) {
            return ApiResponse.fail("INTERVIEW_QUESTIONS_REQUIRED", "候选题不能为空");
        }
        InterviewSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return ApiResponse.fail("INTERVIEW_SESSION_NOT_FOUND", "未找到该访谈会话");
        }
        List<InterviewPrepService.QuestionDraft> drafts = request.questions().stream()
                .map(item -> new InterviewPrepService.QuestionDraft(
                        item.content(), item.focusLabel(), item.riskHint(),
                        Boolean.TRUE.equals(item.core())))
                .toList();
        try {
            List<Long> ids = prepService.createCandidateQuestions(session.getCaseId(), null, drafts);
            return ApiResponse.ok(Map.of("questionIds", ids, "candidateCount", ids.size()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail("INTERVIEW_QUESTIONS_INVALID", e.getMessage());
        }
    }

    /**
     * 表单入口确认题目（题目确认制：只有确认题进入访谈）。
     */
    @PostMapping("/{sessionId}/questions/confirm")
    public ApiResponse<Map<String, Object>> confirmQuestions(
            @PathVariable("sessionId") Long sessionId,
            @RequestHeader(value = "x-ticket", required = false) String ticket,
            @RequestBody ConfirmQuestionsRequest request) {
        if (!stateMachine.validateTicket(sessionId, ticket)) {
            return ApiResponse.fail("INTERVIEW_INVALID_TICKET", "访谈入口凭据无效或已过期");
        }
        InterviewSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return ApiResponse.fail("INTERVIEW_SESSION_NOT_FOUND", "未找到该访谈会话");
        }
        try {
            int confirmed = prepService.confirmQuestions(session.getCaseId(), request.questionIds());
            return ApiResponse.ok(Map.of("confirmedCount", confirmed));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail("INTERVIEW_CONFIRM_INVALID", e.getMessage());
        }
    }

    // ---------- 运行面：话轮与监播 ----------

    /** 访谈入口页读取当前题目和进度；会话凭据只从请求头传递。 */
    @GetMapping("/{sessionId}")
    public ApiResponse<InterviewMonitorResponse> monitor(
            @PathVariable("sessionId") Long sessionId,
            @RequestHeader(value = "x-ticket", required = false) String ticket) {
        if (!stateMachine.validateTicket(sessionId, ticket)) {
            return ApiResponse.fail("INTERVIEW_INVALID_TICKET", "访谈入口凭据无效或已过期");
        }
        InterviewSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return ApiResponse.fail("INTERVIEW_SESSION_NOT_FOUND", "未找到该访谈会话");
        }
        InterviewQuestionEntity current = prepService.currentQuestion(session.getCaseId());
        return ApiResponse.ok(new InterviewMonitorResponse(
                session.getId(),
                session.getCaseId(),
                session.getStatus(),
                session.getMode(),
                current == null ? null : current.getContent(),
                current == null ? null : current.getId(),
                prepService.confirmedCount(session.getCaseId()),
                prepService.answeredCount(session.getCaseId()),
                Integer.valueOf(1).equals(session.getClosingLocked())
        ));
    }

    /**
     * 受访者话轮提交（x-req-id 幂等；SSE 流式回复）。
     * 终态响应按 reqId 暂存 15 分钟：流断后经 GET /{sessionId}/replies/{reqId} 取回。
     */
    @PostMapping(value = "/{sessionId}/turns", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> submitTurn(
            @PathVariable("sessionId") Long sessionId,
            @RequestHeader(value = "x-req-id", required = false) String reqId,
            @RequestHeader(value = "x-ticket", required = false) String ticket,
            @RequestBody TurnRequest request) {
        if (!stateMachine.validateTicket(sessionId, ticket)) {
            return reject("invalid ticket for session " + sessionId);
        }
        String idempotencyKey = (reqId != null && !reqId.isBlank()) ? reqId
                : (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()
                ? request.idempotencyKey() : UUID.randomUUID().toString());

        InterviewRuntimeService.TurnResult result = runtimeService.handleRespondentTurn(
                sessionId, request.content(), idempotencyKey);

        // 终态暂存（断线续取）：TTL 内前端可凭 reqId 重取
        terminalReplies.put(idempotencyKey, new TerminalReply(
                result.reply() == null ? "" : result.reply(),
                result.sessionClosed(), Instant.now().plus(TERMINAL_TTL)));

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        sink.tryEmitNext(result.reply() == null ? "" : result.reply());
        if (result.sessionClosed()) {
            sink.tryEmitNext("[CLOSED]");
        }
        sink.tryEmitComplete();
        return sink.asFlux()
                .map(data -> ServerSentEvent.<String>builder()
                        .event("message")
                        .data(data)
                        .build());
    }

    /**
     * 断线续取：按 reqId 取回暂存的终态响应（15 分钟窗口；未命中返回 404 语义）。
     */
    @GetMapping("/{sessionId}/replies/{reqId}")
    public ApiResponse<Map<String, Object>> reply(
            @PathVariable("sessionId") Long sessionId,
            @PathVariable("reqId") String reqId,
            @RequestHeader(value = "x-ticket", required = false) String ticket) {
        if (!stateMachine.validateTicket(sessionId, ticket)) {
            return ApiResponse.fail("INTERVIEW_INVALID_TICKET", "访谈入口凭据无效或已过期");
        }
        // 惰性清理过期条目
        terminalReplies.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(Instant.now()));
        TerminalReply cached = terminalReplies.get(reqId);
        if (cached == null) {
            return ApiResponse.fail("INTERVIEW_REPLY_NOT_FOUND",
                    "终态响应不存在或已过期（15 分钟窗口），请携带新 x-req-id 重试");
        }
        return ApiResponse.ok(Map.of(
                "reqId", reqId,
                "reply", cached.reply(),
                "closed", cached.closed()));
    }

    /**
     * 监播/字幕流（导演台与受访者页面共用，逐句心跳保持连接）。
     */
    @GetMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable("sessionId") Long sessionId,
            @RequestParam(value = "ticket", required = false) String ticket) {
        if (!stateMachine.validateTicket(sessionId, ticket)) {
            return reject("invalid ticket for session " + sessionId);
        }
        return Flux.interval(Duration.ofSeconds(15))
                .map(i -> ServerSentEvent.<String>builder()
                        .event("heartbeat")
                        .data("alive")
                        .build());
    }

    // ---------- 导演台直连 ----------

    /**
     * 导演指令直连提交（高频干预不经 Supervisor；落库留痕，运行面下一话轮消费）。
     */
    @PostMapping("/{sessionId}/director-commands")
    public ApiResponse<Map<String, Object>> sendDirectorCommand(
            @PathVariable("sessionId") Long sessionId,
            @RequestHeader(value = "x-ticket", required = false) String ticket,
            @RequestBody DirectorCommandRequest request) {
        if (!stateMachine.validateTicket(sessionId, ticket)) {
            return ApiResponse.fail("INTERVIEW_INVALID_TICKET", "访谈入口凭据无效或已过期");
        }
        String command = request.command();
        if (!List.of("WRAP_UP", "NEXT_QUESTION", "PINNED_QUESTION").contains(command)) {
            return ApiResponse.fail("INTERVIEW_COMMAND_INVALID", "未知指令: " + command);
        }
        if ("PINNED_QUESTION".equals(command)
                && (request.pinnedQuestion() == null || request.pinnedQuestion().isBlank())) {
            return ApiResponse.fail("INTERVIEW_COMMAND_INVALID", "PINNED_QUESTION 需要逐字播出的加问文本");
        }
        InterviewSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return ApiResponse.fail("INTERVIEW_SESSION_NOT_FOUND", "未找到该访谈会话");
        }
        InterviewDirectorCommandEntity cmd = new InterviewDirectorCommandEntity();
        cmd.setSessionId(sessionId);
        cmd.setCommand(command);
        cmd.setPinnedQuestion("PINNED_QUESTION".equals(command) ? request.pinnedQuestion() : null);
        cmd.setStatus("PENDING");
        cmd.setSource("DIRECT_REST");
        directorCommandMapper.insert(cmd);
        return ApiResponse.ok(Map.of(
                "commandId", cmd.getId(),
                "command", command,
                "accepted", true));
    }

    private Flux<ServerSentEvent<String>> reject(String message) {
        return Flux.just(ServerSentEvent.<String>builder()
                .event("error")
                .data(message)
                .build());
    }
}
