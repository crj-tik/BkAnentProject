package com.bkanent.interview.runtime;

import com.bkanent.interview.entity.InterviewQuestionEntity;
import com.bkanent.interview.entity.InterviewSessionEntity;
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
import java.util.Map;
import java.util.UUID;

/**
 * 运行面 REST/SSE：前端经 gateway `/interviews/**` 直连，不经过 Supervisor。
 * 凭据只放行本场会话的话轮与监播。
 */
@RestController
@RequestMapping("/interviews")
public class InterviewController {

    private final InterviewRuntimeService runtimeService;
    private final InterviewSessionStateMachine stateMachine;
    private final InterviewSessionMapper sessionMapper;
    private final InterviewPrepService prepService;

    public InterviewController(InterviewRuntimeService runtimeService,
                               InterviewSessionStateMachine stateMachine,
                               InterviewSessionMapper sessionMapper,
                               InterviewPrepService prepService) {
        this.runtimeService = runtimeService;
        this.stateMachine = stateMachine;
        this.sessionMapper = sessionMapper;
        this.prepService = prepService;
    }

    /** 话轮请求体。 */
    public record TurnRequest(String content, String idempotencyKey) {
    }

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

    private Flux<ServerSentEvent<String>> reject(String message) {
        return Flux.just(ServerSentEvent.<String>builder()
                .event("error")
                .data(message)
                .build());
    }
}
