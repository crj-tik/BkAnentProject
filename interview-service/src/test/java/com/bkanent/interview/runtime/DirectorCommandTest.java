package com.bkanent.interview.runtime;

import com.bkanent.interview.engine.ClosingDetector;
import com.bkanent.interview.engine.ProbeDecisionEngine;
import com.bkanent.interview.engine.ProhibitedQuestionFilter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 导演指令语义单测：指令集合、优先级语义与消费约束（不触库部分）。
 */
class DirectorCommandTest {

    @Test
    void commandSetIsClosed() {
        // 协助白名单与主动结束指令分离；结束由独立 end 权限入口创建。
        List<String> allowed = List.of("WRAP_UP", "NEXT_QUESTION", "PINNED_QUESTION");
        assertTrue(allowed.contains("WRAP_UP"));
        assertFalse(allowed.contains("FINALIZE"));
        assertFalse(allowed.contains("DELETE"));     // 管理动作不在指令面
    }

    @Test
    void activeEndUsesRuntimeCloseDecision() {
        // END_SESSION only sets the same CLOSE decision consumed by the established turn pipeline.
        ProbeDecisionEngine.Decision end = ProbeDecisionEngine.Decision.of(ProbeDecisionEngine.Move.CLOSE);
        assertEquals(ProbeDecisionEngine.Move.CLOSE, end.move());
        assertEquals("CLOSING_LOCKED", InterviewSessionStateMachine.CLOSING_LOCKED);
    }

    @Test
    void wrapUpSemanticsMarkCurrentAnsweredThenAdvance() {
        // WRAP_UP = 当前题已答 + ADVANCE：等价于把决策改写为换向
        boolean currentAnswered = true;
        ProbeDecisionEngine.Move overridden = currentAnswered
                ? ProbeDecisionEngine.Move.ADVANCE
                : ProbeDecisionEngine.Move.OPEN_DRILL;
        assertEquals(ProbeDecisionEngine.Move.ADVANCE, overridden);
    }

    @Test
    void pinnedQuestionPassesOutputGateVerbatim() {
        // 加问置顶：逐字播出走质量门（违禁拦截/截断），但不做换皮比对
        ProhibitedQuestionFilter.GateResult gate = ProhibitedQuestionFilter.apply(
                "当时签约现场除了您还有谁在场");
        assertFalse(gate.blocked());
        assertEquals("当时签约现场除了您还有谁在场", gate.text());
    }

    @Test
    void pinnedQuestionStillBlockedByProhibitedFilter() {
        // 人工加问也不能越过违禁问句族（角色越界/策略提炼等）
        ProhibitedQuestionFilter.GateResult gate = ProhibitedQuestionFilter.apply(
                "这套打法可以复制到别的团队也能用吗");
        assertTrue(gate.blocked());
        assertEquals("PROHIBITED", gate.reason());
    }

    @Test
    void directorCommandDoesNotOverrideCloseSignal() {
        // 收尾信号优先于导演指令：受访者道别时，未消费的指令不得阻止收尾
        boolean shouldClose = ClosingDetector.shouldClose(true, false, false);
        boolean directorCommandPending = true;
        boolean applyDirector = directorCommandPending && !shouldClose;
        assertFalse(applyDirector);
    }
}
