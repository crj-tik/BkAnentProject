package com.bkanent.interview.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProbeDecisionEngineTest {

    private ProbeDecisionEngine.DecisionInput input(boolean hard, boolean soft, boolean protest,
                                                    boolean answered, boolean allDone, boolean last,
                                                    int rounds, int depth, int angle) {
        return new ProbeDecisionEngine.DecisionInput("text", hard, soft, protest, answered, allDone,
                last, rounds, depth, angle);
    }

    @Test
    void hardFarewellCloses() {
        assertEquals(ProbeDecisionEngine.Move.CLOSE,
                ProbeDecisionEngine.decide(input(true, false, false, false, false, false, 0, 3, 0)).move());
    }

    @Test
    void repeatProtestAcknowledgesAndSwitches() {
        assertEquals(ProbeDecisionEngine.Move.ACK_AND_SWITCH,
                ProbeDecisionEngine.decide(input(false, false, true, false, false, false, 1, 3, 0)).move());
    }

    @Test
    void lastQuestionAnsweredCloses() {
        assertEquals(ProbeDecisionEngine.Move.CLOSE,
                ProbeDecisionEngine.decide(input(false, false, false, true, false, true, 2, 3, 0)).move());
    }

    @Test
    void allQuestionsDoneCloses() {
        assertEquals(ProbeDecisionEngine.Move.CLOSE,
                ProbeDecisionEngine.decide(input(false, false, false, true, true, false, 1, 3, 0)).move());
    }

    @Test
    void depthHardLimitAdvances() {
        assertEquals(ProbeDecisionEngine.Move.ADVANCE,
                ProbeDecisionEngine.decide(input(false, false, false, false, false, false, 3, 3, 0)).move());
    }

    @Test
    void softCapSwitchesAngle() {
        ProbeDecisionEngine.Decision d = ProbeDecisionEngine.decide(
                input(false, false, false, false, false, false, 2, 3, 0));
        assertEquals(ProbeDecisionEngine.Move.ANGLE, d.move());
        assertEquals(1, d.angleLevel());
    }

    @Test
    void angleLadderExhaustedAdvances() {
        assertEquals(ProbeDecisionEngine.Move.ADVANCE,
                ProbeDecisionEngine.decide(input(false, false, false, false, false, false, 2, 3, 4)).move());
    }

    @Test
    void freeDrillWhenRoomLeft() {
        assertEquals(ProbeDecisionEngine.Move.OPEN_DRILL,
                ProbeDecisionEngine.decide(input(false, false, false, false, false, false, 1, 3, 0)).move());
    }

    @Test
    void coreQuestionGetsDeeperLimit() {
        assertEquals(5, AngleLadder.depthLimit(true));
        assertEquals(3, AngleLadder.depthLimit(false));
    }

    @Test
    void angleLadderFourLevels() {
        assertEquals(MAX_LEVEL, AngleLadder.MAX_LEVEL);
        assertTrue(AngleLadder.angleInstruction(1).contains("时间线"));
        assertTrue(AngleLadder.angleInstruction(2).contains("归属"));
        assertTrue(AngleLadder.angleInstruction(3).contains("结果"));
        assertTrue(AngleLadder.angleInstruction(4).contains("依据"));
    }

    private static final int MAX_LEVEL = AngleLadder.MAX_LEVEL;
}
