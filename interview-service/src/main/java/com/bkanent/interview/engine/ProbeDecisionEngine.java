package com.bkanent.interview.engine;

import java.util.List;

/**
 * 追问决策：五动作（CLOSE / ACK_AND_SWITCH / ANGLE / ADVANCE / OPEN_DRILL），
 * 决策优先级链（源自 S²访谈台 decideProbeMove）：
 * 结束信号 → 重复抗议 → 最后一题收束 → 全部答完 → 追问超硬上限 →
 * 超软上限换角度 → 自由深挖。
 *
 * <p>纯函数。模型不参与决策，只按 {@link ProbeMove} 造句。</p>
 */
public final class ProbeDecisionEngine {

    private ProbeDecisionEngine() {
    }

    /** 追问动作。 */
    public enum Move {
        /** 收尾 */
        CLOSE,
        /** 承认并换向 */
        ACK_AND_SWITCH,
        /** 换角度 */
        ANGLE,
        /** 推进下一题 */
        ADVANCE,
        /** 自由深挖 */
        OPEN_DRILL
    }

    /** 单轮决策输入。 */
    public record DecisionInput(
            String respondentText,
            boolean hardFarewell,
            boolean softComplete,
            boolean repeatProtest,
            boolean currentQuestionAnswered,
            boolean allQuestionsDone,
            boolean isLastQuestion,
            int probeRounds,
            int depthLimit,
            int angleLevel
    ) {
    }

    /** 决策结果：动作 + 该动作的参数。 */
    public record Decision(Move move, int angleLevel) {
        public static Decision of(Move move) {
            return new Decision(move, 0);
        }
    }

    public static Decision decide(DecisionInput input) {
        // 1. 结束信号（硬道别）
        if (input.hardFarewell()) {
            return Decision.of(Move.CLOSE);
        }
        // 2. 重复抗议 → 道歉换向
        if (input.repeatProtest()) {
            return Decision.of(Move.ACK_AND_SWITCH);
        }
        // 3. 最后一题且已答 → 收束
        if (input.isLastQuestion() && input.currentQuestionAnswered()) {
            return Decision.of(Move.CLOSE);
        }
        // 4. 全部题问完（软完结在此收尾）
        if (input.allQuestionsDone()) {
            return Decision.of(Move.CLOSE);
        }
        // 5. 追问超硬上限（深度上限的硬边界）→ 推进下一题
        if (input.probeRounds() >= input.depthLimit()) {
            return Decision.of(Move.ADVANCE);
        }
        // 6. 超软上限 → 换角度（4 级用满即转题）
        int softCap = Math.max(1, input.depthLimit() - 1);
        if (input.probeRounds() >= softCap && input.angleLevel() < AngleLadder.MAX_LEVEL) {
            return new Decision(Move.ANGLE, input.angleLevel() + 1);
        }
        if (input.angleLevel() >= AngleLadder.MAX_LEVEL) {
            return Decision.of(Move.ADVANCE);
        }
        // 7. 自由深挖
        return Decision.of(Move.OPEN_DRILL);
    }
}
