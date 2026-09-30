package com.bkanent.interview.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.interview.config.InterviewRuntimeProperties;
import com.bkanent.interview.entity.InterviewQuestionEntity;
import com.bkanent.interview.entity.InterviewSessionEntity;
import com.bkanent.interview.entity.InterviewTurnEntity;
import com.bkanent.interview.engine.AnswerStatusJudge;
import com.bkanent.interview.engine.AngleLadder;
import com.bkanent.interview.engine.ClosingDetector;
import com.bkanent.interview.engine.ProbeDecisionEngine;
import com.bkanent.interview.engine.ProhibitedQuestionFilter;
import com.bkanent.interview.engine.RepetitionGuard;
import com.bkanent.interview.engine.Sanitizer;
import com.bkanent.interview.engine.SignalDetector;
import com.bkanent.interview.mapper.InterviewQuestionMapper;
import com.bkanent.interview.mapper.InterviewSessionMapper;
import com.bkanent.interview.mapper.InterviewTurnMapper;
import com.bkanent.interview.service.InterviewPrepService;
import com.bkanent.common.skill.core.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 话轮管线：入模脱敏闸 → 信号识别 → 纯函数决策 → 三段记忆装配 →
 * 单次流式造句 → 出模质量门 → 空返回两级兜底 → SSE 下发 + 幂等落库。
 *
 * <p>代码定决策、模型只造句：每轮恰好一次模型调用（空返回重试除外），
 * 访中零跨服务调用（行业大脑为开台预读缓存）。</p>
 */
@Service
public class InterviewRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(InterviewRuntimeService.class);

    /** live-probe 技能名（system 基底，SkillRegistry 直接取正文）。 */
    public static final String LIVE_PROBE_SKILL = "interview-live-probe";

    private final ChatModel chatModel;
    private final SkillRegistry skillRegistry;
    private final InterviewSessionMapper sessionMapper;
    private final InterviewQuestionMapper questionMapper;
    private final InterviewTurnMapper turnMapper;
    private final InterviewPrepService prepService;
    private final InterviewSessionStateMachine stateMachine;
    private final InterviewRuntimeProperties properties;

    public InterviewRuntimeService(ChatModel chatModel,
                                   SkillRegistry skillRegistry,
                                   InterviewSessionMapper sessionMapper,
                                   InterviewQuestionMapper questionMapper,
                                   InterviewTurnMapper turnMapper,
                                   InterviewPrepService prepService,
                                   InterviewSessionStateMachine stateMachine,
                                   InterviewRuntimeProperties properties) {
        this.chatModel = chatModel;
        this.skillRegistry = skillRegistry;
        this.sessionMapper = sessionMapper;
        this.questionMapper = questionMapper;
        this.turnMapper = turnMapper;
        this.prepService = prepService;
        this.stateMachine = stateMachine;
        this.properties = properties;
    }

    /** 单轮回复结果。 */
    public record TurnResult(String reply, String move, boolean sessionClosed) {
    }

    /**
     * 处理一个受访者话轮（完整管线）。
     */
    public TurnResult handleRespondentTurn(Long sessionId, String respondentText, String idempotencyKey) {
        InterviewSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("session not found: " + sessionId);
        }

        // 收尾持久锁：首次收尾后本场强制极短对等道别，不再产生新问题
        if (session.getClosingLocked() != null && session.getClosingLocked() == 1) {
            String lockedReply = ClosingDetector.lockedReplyText();
            persistTurn(sessionId, nextTurnSeq(sessionId), "INTERVIEWER", lockedReply, lockedReply,
                    "CLOSE", null, idempotencyKey);
            return new TurnResult(lockedReply, "CLOSE", true);
        }

        // 会话状态守卫：仅 IN_PROGRESS 接受话轮（已收尾锁定的走上方极短道别分支）
        if (!InterviewSessionStateMachine.IN_PROGRESS.equals(session.getStatus())) {
            throw new IllegalArgumentException(
                    "session " + sessionId + " not in progress: " + session.getStatus());
        }

        // ① 幂等去重：同 idempotency_key 的重复提交直接返回既有回复
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            InterviewTurnEntity existing = turnMapper.selectOne(new LambdaQueryWrapper<InterviewTurnEntity>()
                    .eq(InterviewTurnEntity::getSessionId, sessionId)
                    .eq(InterviewTurnEntity::getIdempotencyKey, idempotencyKey)
                    .eq(InterviewTurnEntity::getRole, "INTERVIEWER")
                    .last("LIMIT 1"));
            if (existing != null) {
                return new TurnResult(existing.getSanitizedContent(), existing.getProbeMove(), false);
            }
        }

        // ② 入模脱敏闸（映射表仅存服务端，不进模型）
        Sanitizer.SanitizeResult sanitized = Sanitizer.sanitize(respondentText);
        persistTurn(sessionId, nextTurnSeq(sessionId), "RESPONDENT", respondentText,
                sanitized.sanitizedText(), null, null, idempotencyKey == null ? UUID.randomUUID().toString() : idempotencyKey + ":in");

        // ③ 信号识别
        SignalDetector.Signals signals = SignalDetector.detect(sanitized.sanitizedText());

        // 悬尾：话没说完 → 静默等待，不回应
        if (signals.trailingOff() && !signals.hardFarewell()) {
            return new TurnResult("", "WAIT", false);
        }

        // ④ 当前题与全部完成判定
        Long caseId = session.getCaseId();
        InterviewQuestionEntity current = prepService.currentQuestion(caseId);
        List<InterviewQuestionEntity> confirmed = questionMapper.selectList(
                new LambdaQueryWrapper<InterviewQuestionEntity>()
                        .eq(InterviewQuestionEntity::getCaseId, caseId)
                        .eq(InterviewQuestionEntity::getConfirmed, 1)
                        .orderByAsc(InterviewQuestionEntity::getSeqNo));
        boolean allDone = current == null;
        boolean isLast = current != null && !confirmed.isEmpty()
                && confirmed.get(confirmed.size() - 1).getId().equals(current.getId());
        boolean currentAnswered = current != null && "ANSWERED".equals(current.getAnswerStatus());
        int probeRounds = current == null ? 0 : (current.getProbeRounds() == null ? 0 : current.getProbeRounds()) + 1;
        int depthLimit = current == null ? 3
                : (current.getDepthLimit() == null ? AngleLadder.depthLimit(current.getIsCore() == 1) : current.getDepthLimit());

        // ⑤ 纯函数决策
        boolean shouldClose = ClosingDetector.shouldClose(signals.hardFarewell(), signals.softComplete(), allDone);
        ProbeDecisionEngine.Decision decision = shouldClose
                ? ProbeDecisionEngine.Decision.of(ProbeDecisionEngine.Move.CLOSE)
                : ProbeDecisionEngine.decide(new ProbeDecisionEngine.DecisionInput(
                        sanitized.sanitizedText(), signals.hardFarewell(), signals.softComplete(),
                        signals.repeatProtest(), currentAnswered, allDone, isLast,
                        probeRounds, depthLimit, 0));

        String reply;
        boolean sessionClosed = false;
        switch (decision.move()) {
            case CLOSE -> {
                reply = ClosingDetector.lockedReplyText();
                sessionClosed = true;
                markAnswered(current);
                // 运行面推进 IN_PROGRESS → CLOSING_LOCKED（收尾锁生效）
                stateMachine.transition(sessionId, InterviewSessionStateMachine.CLOSING_LOCKED,
                        InterviewSessionStateMachine.Actor.RUNTIME);
            }
            case ACK_AND_SWITCH -> {
                // 重复抗议换向：当前题视为已答（受访者抗议说明此前已答过），否则下轮仍指向旧题
                markAnswered(current);
                reply = synthesize(session, sanitized, decision, current,
                        "受访者表示这个问题已经问过了。先真诚道歉，然后直接切换到下一题：" + nextQuestionText(confirmed, current));
            }
            case ANGLE -> reply = synthesize(session, sanitized, decision, current,
                    "当前问题的追问角度：" + AngleLadder.angleInstruction(decision.angleLevel()));
            case ADVANCE -> {
                InterviewQuestionEntity next = nextQuestion(confirmed, current);
                if (next == null) {
                    reply = ClosingDetector.lockedReplyText();
                    sessionClosed = true;
                    stateMachine.transition(sessionId, InterviewSessionStateMachine.CLOSING_LOCKED,
                            InterviewSessionStateMachine.Actor.RUNTIME);
                } else {
                    reply = synthesize(session, sanitized, decision, next,
                            "自然收束当前话题后，提出下一题：" + next.getContent());
                    markAnswered(current);
                }
            }
            case OPEN_DRILL -> reply = synthesize(session, sanitized, decision, current,
                    current == null ? "轻声收尾致谢" : "围绕当前问题自由深挖一个具体细节（时间/人物/动作/结果）");
            default -> reply = "";
        }

        // ⑥ answer_status 判定 + 追问轮数
        //（ADVANCE/CLOSE/ACK_AND_SWITCH 已在分支内标记当前题已答）
        if (current != null
                && (decision.move() == ProbeDecisionEngine.Move.OPEN_DRILL
                || decision.move() == ProbeDecisionEngine.Move.ANGLE)) {
            if (AnswerStatusJudge.shouldMarkAnswered(decision.move(), probeRounds)) {
                markAnswered(current);
            } else {
                InterviewQuestionEntity update = new InterviewQuestionEntity();
                update.setId(current.getId());
                update.setProbeRounds(probeRounds);
                questionMapper.updateById(update);
            }
        }

        // ⑦ 出模质量门（播出前，可拦截可改写）+ ⑧ 空返回兜底
        String finalReply = applyOutputGate(reply, session, current);
        persistTurn(sessionId, nextTurnSeq(sessionId), "INTERVIEWER", finalReply, finalReply,
                decision.move().name(), current == null ? null : current.getId(), idempotencyKey);
        return new TurnResult(finalReply, decision.move().name(), sessionClosed);
    }

    /**
     * 出模质量门：违禁拦截 + 换皮拦截 + 截断；拦截后两级兜底——
     * 直接用清单下一题接住，绝不空追问。
     */
    private String applyOutputGate(String modelOutput, InterviewSessionEntity session,
                                   InterviewQuestionEntity current) {
        if (modelOutput == null || modelOutput.isBlank()) {
            return fallbackNextQuestion(session);
        }
        ProhibitedQuestionFilter.GateResult gate = ProhibitedQuestionFilter.apply(modelOutput);
        if (gate.blocked()) {
            log.info("Output gate blocked ({}), falling back to next question", gate.reason());
            return fallbackNextQuestion(session);
        }
        List<String> asked = recentAskedQuestions(session.getId());
        if (RepetitionGuard.isRephrasedRepeat(gate.text(), asked)) {
            log.info("Repetition guard blocked rephrased repeat");
            return fallbackNextQuestion(session);
        }
        return gate.text();
    }

    /** 兜底：清单下一题原文（代码给定，不走模型）。 */
    private String fallbackNextQuestion(InterviewSessionEntity session) {
        InterviewQuestionEntity next = prepService.currentQuestion(session.getCaseId());
        return next != null ? next.getContent() : ClosingDetector.lockedReplyText();
    }

    /** 近期已问问题台账（近 20 条）。 */
    private List<String> recentAskedQuestions(Long sessionId) {
        return turnMapper.selectList(new LambdaQueryWrapper<InterviewTurnEntity>()
                        .eq(InterviewTurnEntity::getSessionId, sessionId)
                        .eq(InterviewTurnEntity::getRole, "INTERVIEWER")
                        .isNotNull(InterviewTurnEntity::getQuestionId)
                        .orderByDesc(InterviewTurnEntity::getTurnSeq)
                        .last("LIMIT 20"))
                .stream()
                .map(t -> t.getSanitizedContent() == null ? t.getContent() : t.getSanitizedContent())
                .toList();
    }

    /**
     * 单次流式造句：system 基底取 live-probe 技能正文 + 决策指令 + 三段记忆；
     * 空返回以非流式重试一次（两级兜底）。SLO 内未完成取完成前缀。
     */
    private String synthesize(InterviewSessionEntity session, Sanitizer.SanitizeResult sanitized,
                              ProbeDecisionEngine.Decision decision, InterviewQuestionEntity question,
                              String decisionInstruction) {
        String systemBase = liveProbeSystemPrompt();
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemBase
                + "\n\n[当前决策]（代码已定，必须遵守）" + decision.move()
                + (decision.angleLevel() > 0 ? " 第" + decision.angleLevel() + "角度" : "")
                + "\n[决策指令] " + decisionInstruction
                + (question != null ? "\n[当前问题] " + question.getContent() : "")
                + "\n[输出约束] 一句话（≤4小句、≤160字），口语自然，不提问清单外的新话题。"));

        // 三段记忆：>12 轮拆「已问问题台账 + 事实摘要 + 近期对话」
        long turnCount = turnMapper.selectCount(new LambdaQueryWrapper<InterviewTurnEntity>()
                .eq(InterviewTurnEntity::getSessionId, session.getId()));
        if (turnCount > 12) {
            messages.add(new SystemMessage("[已问过的问题]（近义题不得再问）\n"
                    + String.join("\n", recentAskedQuestions(session.getId()))));
            messages.add(new SystemMessage("[受访者已说过的内容摘要]\n"
                    + factSummary(session.getId())));
        }
        List<InterviewTurnEntity> recentTurns = new ArrayList<>(turnMapper.selectList(
                new LambdaQueryWrapper<InterviewTurnEntity>()
                        .eq(InterviewTurnEntity::getSessionId, session.getId())
                        .orderByDesc(InterviewTurnEntity::getTurnSeq)
                        .last("LIMIT 6")));
        // 取最近 6 条后反转为时间正序——对话模型要求历史从旧到新
        java.util.Collections.reverse(recentTurns);
        for (InterviewTurnEntity t : recentTurns) {
            String content = t.getSanitizedContent() == null ? t.getContent() : t.getSanitizedContent();
            if ("RESPONDENT".equals(t.getRole())) {
                messages.add(new UserMessage(content));
            } else if (!content.isBlank()) {
                messages.add(new AssistantMessage(content));
            }
        }
        messages.add(new UserMessage(sanitized.sanitizedText()));

        String streamed = streamOnce(messages);
        if (streamed != null && !streamed.isBlank()) {
            return streamed;
        }
        // 两级兜底第一级：非流式重试
        try {
            String response = chatModel.call(new Prompt(messages)).getResult().getOutput().getText();
            return response == null ? "" : response;
        } catch (Exception e) {
            log.warn("Non-streaming retry failed for session {}: {}", session.getId(), e.getMessage());
            return "";
        }
    }

    /** 流式造句一次：聚合片段，SLO 超时取已收前缀。 */
    private String streamOnce(List<Message> messages) {
        try {
            StringBuilder sb = new StringBuilder();
            Flux<String> flux = chatModel.stream(new Prompt(messages))
                    .map(resp -> resp.getResult() != null && resp.getResult().getOutput() != null
                            && resp.getResult().getOutput().getText() != null
                            ? resp.getResult().getOutput().getText() : "")
                    .take(Duration.ofSeconds(properties.getTurnTimeoutSeconds()));
            flux.doOnNext(sb::append).blockLast();
            return sb.toString();
        } catch (Exception e) {
            log.warn("Streaming synthesis failed: {}", e.getMessage());
            return "";
        }
    }

    /** live-probe 技能正文（运行面 library call 消费路径）。 */
    public String liveProbeSystemPrompt() {
        var skill = skillRegistry.getByName(LIVE_PROBE_SKILL);
        if (skill != null && !skill.systemPrompt().isBlank()) {
            return skill.systemPrompt();
        }
        return "你是一位亲切的深访访谈员。只依据受访者说过的内容追问细节，"
                + "不总结方法论、不做评判、不引入新话题。每次只说一句话。";
    }

    private String factSummary(Long sessionId) {
        List<InterviewTurnEntity> respondentTurns = turnMapper.selectList(
                new LambdaQueryWrapper<InterviewTurnEntity>()
                        .eq(InterviewTurnEntity::getSessionId, sessionId)
                        .eq(InterviewTurnEntity::getRole, "RESPONDENT")
                        .orderByDesc(InterviewTurnEntity::getTurnSeq)
                        .last("LIMIT 20"));
        StringBuilder sb = new StringBuilder();
        for (InterviewTurnEntity t : respondentTurns) {
            sb.append("- ").append(t.getSanitizedContent() == null ? t.getContent() : t.getSanitizedContent())
                    .append('\n');
        }
        return sb.length() == 0 ? "（暂无）" : sb.toString();
    }

    private InterviewQuestionEntity nextQuestion(List<InterviewQuestionEntity> confirmed,
                                                 InterviewQuestionEntity current) {
        if (current == null) {
            return confirmed.isEmpty() ? null : confirmed.get(0);
        }
        for (int i = 0; i < confirmed.size(); i++) {
            if (confirmed.get(i).getId().equals(current.getId()) && i + 1 < confirmed.size()) {
                return confirmed.get(i + 1);
            }
        }
        return null;
    }

    private String nextQuestionText(List<InterviewQuestionEntity> confirmed, InterviewQuestionEntity current) {
        InterviewQuestionEntity next = nextQuestion(confirmed, current);
        return next != null ? next.getContent() : "自然收尾致谢";
    }

    private void markAnswered(InterviewQuestionEntity question) {
        if (question == null) {
            return;
        }
        InterviewQuestionEntity update = new InterviewQuestionEntity();
        update.setId(question.getId());
        update.setAnswerStatus("ANSWERED");
        questionMapper.updateById(update);
    }

    private int nextTurnSeq(Long sessionId) {
        InterviewTurnEntity last = turnMapper.selectOne(new LambdaQueryWrapper<InterviewTurnEntity>()
                .eq(InterviewTurnEntity::getSessionId, sessionId)
                .orderByDesc(InterviewTurnEntity::getTurnSeq)
                .last("LIMIT 1"));
        return last == null || last.getTurnSeq() == null ? 1 : last.getTurnSeq() + 1;
    }

    /** 幂等落库（写队列语义：唯一键防重，失败由补偿器退避重试）。 */
    private void persistTurn(Long sessionId, int seq, String role, String content, String sanitized,
                             String move, Long questionId, String idempotencyKey) {
        InterviewTurnEntity turn = new InterviewTurnEntity();
        turn.setSessionId(sessionId);
        turn.setTurnSeq(seq);
        turn.setRole(role);
        turn.setContent(content == null ? "" : content);
        turn.setSanitizedContent(sanitized == null ? content : sanitized);
        turn.setProbeMove(move);
        turn.setQuestionId(questionId);
        turn.setIdempotencyKey(idempotencyKey);
        turn.setPersistRetries(0);
        try {
            turnMapper.insert(turn);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.debug("Duplicate turn persist ignored (session={}, key={})", sessionId, idempotencyKey);
        }
    }
}
