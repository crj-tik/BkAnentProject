package com.bkanent.interview.engine;

/**
 * answer_status 判定：代码规则秒判（ADVANCE/CLOSE 即标已答），
 * 保底「该题已答 ≥3 轮升级为已答」。
 */
public final class AnswerStatusJudge {

    private AnswerStatusJudge() {
    }

    public static final int MIN_ROUNDS_FORCED_ANSWERED = 3;

    /**
     * 判定当前题是否应标记为已答。
     *
     * @param move         本轮决策动作
     * @param probeRounds  该题已追问轮数（含本轮）
     */
    public static boolean shouldMarkAnswered(ProbeDecisionEngine.Move move, int probeRounds) {
        if (move == ProbeDecisionEngine.Move.ADVANCE || move == ProbeDecisionEngine.Move.CLOSE) {
            return true;
        }
        return probeRounds >= MIN_ROUNDS_FORCED_ANSWERED;
    }
}
