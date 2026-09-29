package com.bkanent.interview.engine;

/**
 * 深度分级 + 换角度阶梯。
 *
 * <p>深度分级：核心题（前两题）追 4–5 层、一般题 2–3 层。
 * 换角度阶梯四级：补时间线前后节点 → 确认动作归属 → 补可观察结果变化 →
 * 核对判断依据；4 级用满即转题，不换皮重问。</p>
 */
public final class AngleLadder {

    private AngleLadder() {
    }

    public static final int MAX_LEVEL = 4;

    /**
     * 深度上限：核心题 5，一般题 3。
     */
    public static int depthLimit(boolean isCore) {
        return isCore ? 5 : 3;
    }

    /**
     * 换角度阶梯第 N 级的追问方向。
     */
    public static String angleInstruction(int level) {
        return switch (Math.min(Math.max(level, 1), MAX_LEVEL)) {
            case 1 -> "补时间线前后节点：这件事之前发生了什么、之后发生了什么";
            case 2 -> "确认动作归属：这个动作具体是谁做的、谁决定的";
            case 3 -> "补可观察结果变化：做完之后什么变得不一样了、你怎么看出来的";
            case 4 -> "核对判断依据：你依据什么做出这个判断";
            default -> "核对判断依据";
        };
    }
}
