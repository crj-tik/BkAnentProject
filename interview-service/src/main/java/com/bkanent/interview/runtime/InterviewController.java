package com.bkanent.interview.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.interview.entity.InterviewDirectorCommandEntity;
import com.bkanent.interview.entity.InterviewQuestionEntity;
import com.bkanent.interview.entity.InterviewSessionEntity;
import com.bkanent.interview.engine.Sanitizer;
import com.bkanent.interview.mapper.InterviewDirectorCommandMapper;
import com.bkanent.interview.mapper.InterviewSessionMapper;
import com.bkanent.interview.service.InterviewPrepService;
import com.bkanent.interview.config.InterviewAuthorizationProperties;
import com.bkanent.interview.security.InterviewAuthorizationScope;
import com.bkanent.interview.service.InterviewParticipantService;
import com.bkanent.interview.service.InterviewParticipantTicketService;
import com.bkanent.interview.service.InterviewAuthorizationService;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.model.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
    private final com.bkanent.interview.service.InterviewReportService reportService;
    private final InterviewParticipantService participantService;
    private final InterviewAuthorizationProperties authorizationProperties;
    private final com.bkanent.interview.service.InterviewAuthorizationService authorizationService;
    private final InterviewParticipantTicketService participantTicketService;

    /** 断线续取：reqId → 终态响应（含过期时间，惰性清理）。 */
    private final ConcurrentMap<String, TerminalReply> terminalReplies = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public InterviewController(InterviewRuntimeService runtimeService,
                               InterviewSessionStateMachine stateMachine,
                               InterviewSessionMapper sessionMapper,
                               InterviewPrepService prepService,
                               InterviewDirectorCommandMapper directorCommandMapper,
                               com.bkanent.interview.service.InterviewReportService reportService,
                               InterviewParticipantService participantService,
                               InterviewAuthorizationProperties authorizationProperties,
                               com.bkanent.interview.service.InterviewAuthorizationService authorizationService,
                               InterviewParticipantTicketService participantTicketService) {
        this.runtimeService = runtimeService;
        this.stateMachine = stateMachine;
        this.sessionMapper = sessionMapper;
        this.prepService = prepService;
        this.directorCommandMapper = directorCommandMapper;
        this.reportService = reportService;
        this.participantService = participantService;
        this.authorizationProperties = authorizationProperties;
        this.authorizationService = authorizationService;
        this.participantTicketService = participantTicketService;
    }

    public InterviewController(InterviewRuntimeService runtimeService,
                               InterviewSessionStateMachine stateMachine,
                               InterviewSessionMapper sessionMapper,
                               InterviewPrepService prepService,
                               InterviewDirectorCommandMapper directorCommandMapper,
                               com.bkanent.interview.service.InterviewReportService reportService,
                               InterviewParticipantService participantService,
                               InterviewAuthorizationProperties authorizationProperties,
                               com.bkanent.interview.service.InterviewAuthorizationService authorizationService) {
        this(runtimeService, stateMachine, sessionMapper, prepService, directorCommandMapper,
                reportService, participantService, authorizationProperties, authorizationService, null);
    }

    public InterviewController(InterviewRuntimeService runtimeService,
                               InterviewSessionStateMachine stateMachine,
                               InterviewSessionMapper sessionMapper,
                               InterviewPrepService prepService,
                               InterviewDirectorCommandMapper directorCommandMapper,
                               com.bkanent.interview.service.InterviewReportService reportService) {
        this(runtimeService, stateMachine, sessionMapper, prepService, directorCommandMapper,
                reportService, null, new InterviewAuthorizationProperties(), null);
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
            String creatorWorkNo,
            Long respondentUserId) {
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

    public record ParticipantCredentialRequest(String purpose) { }

    public record InviteRespondentRequest(Long userId) { }

    /** List only the caller's confirmed participation relations, or the explicit business-wide read scope. */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listSessions(
            @RequestParam(value = "status", required = false) String status) {
        if (!authorizationProperties.isEnabled() || authorizationService == null) {
            return ApiResponse.fail("INTERVIEW_AUTHORIZATION_DISABLED", "访谈关系查询暂不可用");
        }
        try {
            List<Long> visibleIds = authorizationService.visibleSessionIds();
            if (visibleIds != null && visibleIds.isEmpty()) return ApiResponse.ok(List.of());
            var query = new LambdaQueryWrapper<InterviewSessionEntity>()
                    .eq(status != null && !status.isBlank(), InterviewSessionEntity::getStatus,
                            status == null ? "" : status)
                    .orderByDesc(InterviewSessionEntity::getId);
            if (visibleIds != null) query.in(InterviewSessionEntity::getId, visibleIds);
            query.last("LIMIT 100");
            List<Map<String, Object>> rows = sessionMapper.selectList(query).stream().map(session -> {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("sessionId", session.getId());
                row.put("caseId", session.getCaseId());
                row.put("status", session.getStatus());
                row.put("mode", session.getMode());
                row.put("entrySource", session.getEntrySource());
                row.put("createdAt", session.getCreatedAt());
                return row;
            }).toList();
            return ApiResponse.ok(rows);
        } catch (AuthzException denied) {
            return ApiResponse.fail(denied.code().name(), "当前身份不能查询访谈列表");
        }
    }

    /** Pending invites are visible only to the authenticated selected respondent. */
    @GetMapping("/participants/invitations")
    public ApiResponse<List<Map<String, Object>>> pendingInvitations() {
        if (!authorizationProperties.isEnabled() || participantService == null) {
            return ApiResponse.fail("INTERVIEW_AUTHORIZATION_DISABLED", "受访邀请查询暂不可用");
        }
        try {
            InterviewAuthorizationScope.requirePermission(InterviewAuthorizationService.RESPOND);
            long actorId = InterviewAuthorizationScope.requireActorId();
            List<Map<String, Object>> invitations = participantService.pendingInvitations(actorId).stream()
                    .map(invitation -> Map.<String, Object>of(
                            "sessionId", invitation.getSessionId(),
                            "invitedAt", invitation.getCreatedAt() == null ? "" : invitation.getCreatedAt().toString()))
                    .toList();
            return ApiResponse.ok(invitations);
        } catch (AuthzException denied) {
            return ApiResponse.fail(denied.code().name(), "当前身份不能查询受访邀请");
        }
    }

    /** User selection is a target invitation only; the selected user must authenticate to confirm it. */
    @PostMapping("/{sessionId}/participants/respond/invite")
    public ApiResponse<Map<String, Object>> inviteRespondent(@PathVariable("sessionId") Long sessionId,
                                                               @RequestBody InviteRespondentRequest request) {
        if (!authorizationProperties.isEnabled() || participantService == null || authorizationService == null) {
            return ApiResponse.fail("INTERVIEW_AUTHORIZATION_DISABLED", "受访邀请暂不可用");
        }
        try {
            authorizationService.requireInitiator(sessionId, InterviewAuthorizationService.INITIATE);
            long inviter = InterviewAuthorizationScope.requireActorId();
            if (request == null || request.userId() == null || request.userId() <= 0
                    || !participantService.inviteRespondent(sessionId, inviter, request.userId())) {
                return ApiResponse.fail("INTERVIEW_INVITATION_INVALID", "无法创建该受访邀请");
            }
            return ApiResponse.ok(Map.of("sessionId", sessionId, "state", InterviewParticipantService.PENDING));
        } catch (AuthzException denied) {
            return ApiResponse.fail(denied.code().name(), "当前身份不能邀请受访者");
        }
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
        if (!authorizationProperties.isEnabled() || authorizationService == null) {
            return ApiResponse.fail("INTERVIEW_AUTHORIZATION_DISABLED", "访谈鉴权未启用，暂不能创建会话");
        }
        Long actorId = null;
        try {
            InterviewAuthorizationScope.requirePermission(InterviewAuthorizationService.INITIATE);
            actorId = InterviewAuthorizationScope.requireActorId();
        } catch (AuthzException denied) {
            return ApiResponse.fail(denied.code().name(), "当前身份没有发起访谈权限");
        }
        try {
            Map<String, Object> opened = prepService.openCase(
                    request.scene(), request.respondentRole(), request.caseStatus(), request.objective(),
                    request.divisionName(), request.regionName(), request.businessDistrict(),
                    request.referenceMinutes(), "FORM", request.creatorWorkNo(), actorId, request.respondentUserId());
            return ApiResponse.ok(opened);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail("INTERVIEW_OPEN_INVALID", e.getMessage());
        }
    }

    /** Credentials are delivered only to the authenticated browser caller and are never returned by A2A tools. */
    @PostMapping("/{sessionId}/credentials")
    public ResponseEntity<ApiResponse<Map<String, Object>>> issueParticipantCredential(
            @PathVariable("sessionId") Long sessionId,
            @RequestBody ParticipantCredentialRequest request) {
        HttpHeaders headers = noStoreHeaders();
        if (!authorizationProperties.isEnabled() || participantTicketService == null) {
            return ResponseEntity.status(503).headers(headers)
                    .body(ApiResponse.fail("INTERVIEW_AUTHORIZATION_DISABLED", "访谈凭据领取暂不可用"));
        }
        try {
            if (request == null) throw new AuthzException(com.bkanent.common.authz.AuthzErrorCode.PERMISSION_DENIED);
            InterviewParticipantTicketService.IssuedCredential issued =
                    participantTicketService.issue(sessionId, request.purpose());
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("credentialId", issued.credentialId());
            body.put("credential", issued.credential());
            body.put("purpose", issued.purpose());
            body.put("expiresAt", issued.expiresAt().toString());
            body.put("headerName", "X-Ticket");
            return ResponseEntity.ok().headers(headers).body(ApiResponse.ok(body));
        } catch (AuthzException denied) {
            return ResponseEntity.status(403).headers(headers)
                    .body(ApiResponse.fail(denied.code().name(), "当前身份不能领取此场次凭据"));
        }
    }

    /** A caller may revoke its own credential; a session initiator may revoke a credential for that session. */
    @org.springframework.web.bind.annotation.DeleteMapping("/{sessionId}/credentials/{credentialId}")
    public ApiResponse<Map<String, Object>> revokeParticipantCredential(
            @PathVariable("sessionId") Long sessionId,
            @PathVariable("credentialId") Long credentialId) {
        if (!authorizationProperties.isEnabled() || participantTicketService == null) {
            return ApiResponse.fail("INTERVIEW_AUTHORIZATION_DISABLED", "访谈凭据撤销暂不可用");
        }
        try {
            boolean revoked = participantTicketService.revoke(sessionId, credentialId);
            if (!revoked) return ApiResponse.fail("INTERVIEW_CREDENTIAL_NOT_FOUND", "未找到可撤销的场次凭据");
            return ApiResponse.ok(Map.of("credentialId", credentialId, "revoked", true));
        } catch (AuthzException denied) {
            return ApiResponse.fail(denied.code().name(), "当前身份不能撤销此场次凭据");
        }
    }

    /** A selected respondent confirms only their own pending relation using their signed actor context. */
    @PostMapping("/{sessionId}/participants/respond/confirm")
    public ApiResponse<Map<String, Object>> confirmRespondent(@PathVariable("sessionId") Long sessionId) {
        if (!authorizationProperties.isEnabled() || participantService == null) {
            return ApiResponse.fail("INTERVIEW_AUTHORIZATION_DISABLED", "受访者确认暂不可用");
        }
        try {
            InterviewAuthorizationScope.requirePermission("interview.respond");
            long actorId = InterviewAuthorizationScope.requireActorId();
            if (!participantService.confirmRespondent(sessionId, actorId)) {
                return ApiResponse.fail("INTERVIEW_PARTICIPANT_NOT_FOUND", "当前用户没有待确认的受访关系");
            }
            return ApiResponse.ok(Map.of("sessionId", sessionId, "state", InterviewParticipantService.CONFIRMED));
        } catch (AuthzException denied) {
            return ApiResponse.fail(denied.code().name(), "当前身份不能确认此受访关系");
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
        String authFailure = sessionAuthorizationFailure(sessionId, ticket, SessionPolicy.INITIATE);
        if (authFailure != null) return ApiResponse.fail(authFailure, "当前身份不能修改此访谈题单");
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
        String authFailure = sessionAuthorizationFailure(sessionId, ticket, SessionPolicy.INITIATE);
        if (authFailure != null) return ApiResponse.fail(authFailure, "当前身份不能确认此访谈题单");
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

    /**
     * 表单入口显式开始访谈（发起人点「开始访谈」：QUESTIONS_CONFIRMED → IN_PROGRESS）。
     * 不重签 ticket——开台响应里的那张在题目确认阶段已被前端持有，重签会作废它；
     * 凭据单活，状态推进即可。对话式入口无此端点（startInterviewSession 经 Supervisor 背书签发）。
     */
    @PostMapping("/{sessionId}/start")
    public ApiResponse<Map<String, Object>> start(
            @PathVariable("sessionId") Long sessionId,
            @RequestHeader(value = "x-ticket", required = false) String ticket) {
        String authFailure = sessionAuthorizationFailure(sessionId, ticket, SessionPolicy.INITIATE);
        if (authFailure != null) return ApiResponse.fail(authFailure, "当前身份不能启动此访谈");
        try {
            InterviewSessionEntity session = stateMachine.transition(sessionId,
                    InterviewSessionStateMachine.IN_PROGRESS,
                    InterviewSessionStateMachine.Actor.GOVERNANCE);
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("sessionId", sessionId);
            result.put("status", session.getStatus());
            result.put("mode", session.getMode());
            result.put("turnsPath", "/interviews/" + sessionId + "/turns");
            result.put("streamPath", "/interviews/" + sessionId + "/stream");
            result.put("credentialClaimPath", "/interviews/" + sessionId + "/credentials");
            return ApiResponse.ok(result);
        } catch (InterviewSessionStateMachine.IllegalTransitionException e) {
            return ApiResponse.fail("INTERVIEW_START_INVALID", e.getMessage());
        }
    }

    // ---------- 运行面：话轮与监播 ----------

    /** 访谈入口页读取当前题目和进度；会话凭据只从请求头传递。 */
    @GetMapping("/{sessionId}")
    public ApiResponse<InterviewMonitorResponse> monitor(
            @PathVariable("sessionId") Long sessionId,
            @RequestHeader(value = "x-ticket", required = false) String ticket) {
        String authFailure = sessionAuthorizationFailure(sessionId, ticket, SessionPolicy.READ);
        if (authFailure != null) return ApiResponse.fail(authFailure, "当前身份不能读取此访谈");
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
        String authFailure = sessionAuthorizationFailure(sessionId, ticket, SessionPolicy.RESPOND);
        if (authFailure != null) {
            return reject(authFailure);
        }
        if (Sanitizer.containsCredentialShape(reqId)
                || request != null && Sanitizer.containsCredentialShape(request.idempotencyKey())) {
            return reject("INTERVIEW_REQUEST_ID_INVALID");
        }
        String idempotencyKey = (reqId != null && !reqId.isBlank()) ? reqId
                : (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()
                ? request.idempotencyKey() : UUID.randomUUID().toString());

        InterviewRuntimeService.TurnResult result = runtimeService.handleRespondentTurn(
                sessionId, request.content(), idempotencyKey);

        // 终态暂存（断线续取）：TTL 内前端可凭 reqId 重取
        terminalReplies.put(sessionId + ":" + idempotencyKey, new TerminalReply(
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
        if (Sanitizer.containsCredentialShape(reqId)) {
            return ApiResponse.fail("INTERVIEW_REQUEST_ID_INVALID", "回执编号格式无效");
        }
        String authFailure = sessionAuthorizationFailure(sessionId, ticket, SessionPolicy.READ);
        if (authFailure != null) return ApiResponse.fail(authFailure, "当前身份不能读取此访谈回执");
        // 惰性清理过期条目
        terminalReplies.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(Instant.now()));
        InterviewRuntimeService.TurnResult persisted = runtimeService.completedTurn(sessionId, reqId);
        if (persisted != null) return ApiResponse.ok(Map.of("reqId", reqId, "reply", persisted.reply() == null ? "" : persisted.reply(), "closed", persisted.sessionClosed()));
        TerminalReply cached = terminalReplies.get(sessionId + ":" + reqId);
        if (cached == null) {
            return ApiResponse.fail("INTERVIEW_REPLY_NOT_FOUND",
                    "尚未找到本场原请求的完整回执，请保留原请求编号核对，不要重新提交");
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
            @RequestHeader(value = "x-ticket", required = false) String ticket) {
        if (!authorizationProperties.isEnabled()) return reject("INTERVIEW_AUTHORIZATION_DISABLED");
        if (authorizationService == null || participantTicketService == null) {
            return reject("INTERVIEW_AUTHORIZATION_UNAVAILABLE");
        }
        InterviewParticipantTicketService.CredentialLease lease;
        try {
            authorizationService.requireRead(sessionId);
            lease = participantTicketService.validate(sessionId, ticket, InterviewParticipantTicketService.READ);
        } catch (AuthzException denied) {
            return reject("INTERVIEW_AUTHORIZATION_DENIED");
        }
        if (lease == null) return reject("INTERVIEW_INVALID_CREDENTIAL");
        long deadline = Math.min(InterviewAuthorizationScope.expiresAtEpochSecond(),
                lease.expiresAt().atZone(java.time.ZoneId.systemDefault()).toEpochSecond());
        return Flux.interval(Duration.ofSeconds(15))
                .takeWhile(i -> Instant.now().getEpochSecond() < deadline
                        && participantTicketService.isCurrent(lease))
                .map(i -> ServerSentEvent.<String>builder()
                        .event("heartbeat")
                        .data("alive")
                        .build());
    }

    // ---------- 报告任务中心（任务列表/详情，前端分页拉取） ----------

    /**
     * 报告任务列表（分页）：按创建人与状态过滤，提交时间倒序。
     * creatorWorkNo 为空时返回全部（平台尚无组织鉴权，公开只读口径）。
     */
    @GetMapping("/reports/tasks")
    public ApiResponse<Map<String, Object>> listReportTasks(
            @RequestParam(value = "creatorWorkNo", required = false) String creatorWorkNo,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        try {
            if (!authorizationProperties.isEnabled()) {
                return ApiResponse.fail("INTERVIEW_AUTHORIZATION_DISABLED", "访谈报告读取暂不可用");
            }
            return ApiResponse.ok(reportService.listCaseCardTasks(creatorWorkNo, status, page, pageSize,
                    authorizationService.visibleCaseIds()));
        } catch (AuthzException denied) {
            return ApiResponse.fail(denied.code().name(), "当前身份不能读取访谈报告");
        }
    }

    /**
     * 报告任务详情（含已生成报告的结构化结论、核验状态与缺失分类）。
     */
    @GetMapping("/reports/tasks/{taskId}")
    public ApiResponse<Map<String, Object>> reportTaskDetail(
            @PathVariable("taskId") Long taskId) {
        if (!authorizationProperties.isEnabled()) {
            return ApiResponse.fail("INTERVIEW_AUTHORIZATION_DISABLED", "访谈报告读取暂不可用");
        }
        List<Long> visibleCaseIds;
        try {
            visibleCaseIds = authorizationProperties.isEnabled() ? authorizationService.visibleCaseIds() : null;
        } catch (AuthzException denied) {
            return ApiResponse.fail(denied.code().name(), "当前身份不能读取访谈报告");
        }
        Map<String, Object> detail = reportService.getTaskDetail(taskId, visibleCaseIds);
        if (detail.containsKey("error")) {
            return ApiResponse.fail("INTERVIEW_TASK_NOT_FOUND", String.valueOf(detail.get("error")));
        }
        return ApiResponse.ok(detail);
    }

    // ---------- 报告跨任务搜索（报告中心检索，任务详情端点提供全文） ----------

    /**
     * 跨任务报告搜索（分页）：关键词匹配报告正文，可叠加创建人过滤；报告维度、时间倒序。
     */
    @GetMapping("/reports/search")
    public ApiResponse<Map<String, Object>> searchReports(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "creatorWorkNo", required = false) String creatorWorkNo,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        try {
            if (!authorizationProperties.isEnabled()) {
                return ApiResponse.fail("INTERVIEW_AUTHORIZATION_DISABLED", "访谈报告搜索暂不可用");
            }
            return ApiResponse.ok(reportService.searchReports(keyword, creatorWorkNo, page, pageSize,
                    authorizationService.visibleCaseIds()));
        } catch (AuthzException denied) {
            return ApiResponse.fail(denied.code().name(), "当前身份不能搜索访谈报告");
        }
    }

    // ---------- 导演台直连 ----------

    /**
     * 导演指令直连提交（高频干预不经 Supervisor；落库留痕，运行面下一话轮消费）。
     */
    @PostMapping("/{sessionId}/director-commands")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public ApiResponse<Map<String, Object>> sendDirectorCommand(
            @PathVariable("sessionId") Long sessionId,
            @RequestHeader(value = "x-ticket", required = false) String ticket,
            @RequestBody DirectorCommandRequest request) {
        String authFailure = sessionAuthorizationFailure(sessionId, ticket, SessionPolicy.ASSIST);
        if (authFailure != null) return ApiResponse.fail(authFailure, "当前身份不能协助此访谈");
        if (request == null || Sanitizer.containsCredentialShape(request.pinnedQuestion())) {
            return ApiResponse.fail("INTERVIEW_COMMAND_INVALID", "协助指令包含无效内容");
        }
        String command = request.command();
        if (!List.of("WRAP_UP", "NEXT_QUESTION", "PINNED_QUESTION").contains(command)) {
            return ApiResponse.fail("INTERVIEW_COMMAND_INVALID", "未知指令: " + command);
        }
        if ("PINNED_QUESTION".equals(command)
                && (request.pinnedQuestion() == null || request.pinnedQuestion().isBlank())) {
            return ApiResponse.fail("INTERVIEW_COMMAND_INVALID", "PINNED_QUESTION 需要逐字播出的加问文本");
        }
        InterviewSessionEntity session = sessionMapper.selectOne(new LambdaQueryWrapper<InterviewSessionEntity>()
                .eq(InterviewSessionEntity::getId, sessionId)
                .eq(InterviewSessionEntity::getStatus, InterviewSessionStateMachine.IN_PROGRESS)
                .last("LIMIT 1 FOR UPDATE"));
        if (session == null) {
            return ApiResponse.fail("INTERVIEW_COMMAND_INVALID", "协助指令仅接受进行中的访谈");
        }
        InterviewDirectorCommandEntity cmd = new InterviewDirectorCommandEntity();
        cmd.setSessionId(sessionId);
        cmd.setCommand(command);
        cmd.setPinnedQuestion("PINNED_QUESTION".equals(command) ? request.pinnedQuestion() : null);
        cmd.setStatus("PENDING");
        cmd.setSource("DIRECT_REST");
        authorizationService.stampAuthorizedCommand(cmd);
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

    private String sessionAuthorizationFailure(Long sessionId, String ticket, SessionPolicy policy) {
        if (!authorizationProperties.isEnabled()) {
            return "INTERVIEW_AUTHORIZATION_DISABLED";
        }
        if (authorizationService == null || participantTicketService == null) {
            return "INTERVIEW_AUTHORIZATION_UNAVAILABLE";
        }
        try {
            switch (policy) {
                case READ -> authorizationService.requireRead(sessionId);
                case RESPOND -> authorizationService.requireRespondent(sessionId);
                case INITIATE -> authorizationService.requireInitiator(sessionId,
                        InterviewAuthorizationService.INITIATE);
                case ASSIST -> authorizationService.requireInitiator(sessionId,
                        InterviewAuthorizationService.ASSIST);
            }
            String purpose = policy.ticketPurpose();
            return participantTicketService.validate(sessionId, ticket, purpose) == null
                    ? "INTERVIEW_INVALID_CREDENTIAL" : null;
        } catch (AuthzException denied) {
            return "INTERVIEW_AUTHORIZATION_DENIED";
        }
    }

    private static HttpHeaders noStoreHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store, no-cache, must-revalidate");
        headers.setPragma("no-cache");
        headers.setExpires(0);
        return headers;
    }

    private enum SessionPolicy {
        READ(InterviewParticipantTicketService.READ),
        RESPOND(InterviewParticipantTicketService.RESPOND),
        INITIATE(InterviewParticipantTicketService.MANAGE),
        ASSIST(InterviewParticipantTicketService.MANAGE);

        private final String ticketPurpose;
        SessionPolicy(String ticketPurpose) { this.ticketPurpose = ticketPurpose; }
        String ticketPurpose() { return ticketPurpose; }
    }
}
