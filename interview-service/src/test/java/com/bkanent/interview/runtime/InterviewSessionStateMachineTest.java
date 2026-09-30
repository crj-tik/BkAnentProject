package com.bkanent.interview.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 状态机权限表与前置状态单测（防权限表错位回归）：
 * 每个用例对应一个真实调用点。
 */
class InterviewSessionStateMachineTest {

    // ---- 调用点对齐：每个 transition(...) 调用必须能通过权限表 ----

    @Test
    void governanceStartsSession() {
        // InterviewTools.startInterviewSession: QUESTIONS_CONFIRMED -> IN_PROGRESS, GOVERNANCE
        assertTrue(InterviewSessionStateMachine.isAllowed(
                InterviewSessionStateMachine.IN_PROGRESS, InterviewSessionStateMachine.Actor.GOVERNANCE));
    }

    @Test
    void governanceConfirmsQuestions() {
        // InterviewPrepService.confirmQuestions: DRAFT -> QUESTIONS_CONFIRMED, GOVERNANCE
        assertTrue(InterviewSessionStateMachine.isAllowed(
                InterviewSessionStateMachine.QUESTIONS_CONFIRMED, InterviewSessionStateMachine.Actor.GOVERNANCE));
    }

    @Test
    void runtimeClosesSession() {
        // InterviewRuntimeService CLOSE/ADVANCE 兜底 + InterviewTools.finalizeInterview: -> CLOSING_LOCKED, RUNTIME
        assertTrue(InterviewSessionStateMachine.isAllowed(
                InterviewSessionStateMachine.CLOSING_LOCKED, InterviewSessionStateMachine.Actor.RUNTIME));
    }

    @Test
    void compensatorDrivesCollectionAndArchive() {
        // InterviewCollectionService: -> COLLECT_PENDING 与 -> ARCHIVED, COMPENSATOR
        assertTrue(InterviewSessionStateMachine.isAllowed(
                InterviewSessionStateMachine.COLLECT_PENDING, InterviewSessionStateMachine.Actor.COMPENSATOR));
        assertTrue(InterviewSessionStateMachine.isAllowed(
                InterviewSessionStateMachine.ARCHIVED, InterviewSessionStateMachine.Actor.COMPENSATOR));
    }

    // ---- 权限表反向约束：越权必须被拒 ----

    @Test
    void runtimeCannotStartOrConfirm() {
        assertFalse(InterviewSessionStateMachine.isAllowed(
                InterviewSessionStateMachine.IN_PROGRESS, InterviewSessionStateMachine.Actor.RUNTIME));
        assertFalse(InterviewSessionStateMachine.isAllowed(
                InterviewSessionStateMachine.QUESTIONS_CONFIRMED, InterviewSessionStateMachine.Actor.RUNTIME));
    }

    @Test
    void governanceCannotCloseDirectly() {
        // 治理面不能直接把会话推到收尾锁（须由运行面或导演指令触发的运行面收束）
        assertFalse(InterviewSessionStateMachine.isAllowed(
                InterviewSessionStateMachine.CLOSING_LOCKED, InterviewSessionStateMachine.Actor.GOVERNANCE));
    }

    @Test
    void compensatorCannotEnterRunningPhase() {
        assertFalse(InterviewSessionStateMachine.isAllowed(
                InterviewSessionStateMachine.CLOSING_LOCKED, InterviewSessionStateMachine.Actor.COMPENSATOR));
    }

    // ---- 前置状态链完整性 ----

    @Test
    void statusChainIsLinear() {
        assertEquals(InterviewSessionStateMachine.DRAFT,
                InterviewSessionStateMachine.expectedPrevious(InterviewSessionStateMachine.QUESTIONS_CONFIRMED));
        assertEquals(InterviewSessionStateMachine.QUESTIONS_CONFIRMED,
                InterviewSessionStateMachine.expectedPrevious(InterviewSessionStateMachine.IN_PROGRESS));
        assertEquals(InterviewSessionStateMachine.IN_PROGRESS,
                InterviewSessionStateMachine.expectedPrevious(InterviewSessionStateMachine.CLOSING_LOCKED));
        assertEquals(InterviewSessionStateMachine.CLOSING_LOCKED,
                InterviewSessionStateMachine.expectedPrevious(InterviewSessionStateMachine.COLLECT_PENDING));
        assertEquals(InterviewSessionStateMachine.COLLECT_PENDING,
                InterviewSessionStateMachine.expectedPrevious(InterviewSessionStateMachine.ARCHIVED));
    }
}
