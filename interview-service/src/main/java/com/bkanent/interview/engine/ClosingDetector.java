package com.bkanent.interview.engine;

/**
 * 收尾判定：硬道别无条件收尾；软完结仅在全部确认题问完才收尾；
 * 收尾持久锁——首次收尾后本场强制极短对等道别。
 */
public final class ClosingDetector {

    private ClosingDetector() {
    }

    /**
     * 判定本轮是否应进入收尾。
     *
     * @param hardFarewell       硬道别信号
     * @param softComplete       软完结信号
     * @param allQuestionsDone   全部确认题是否问完
     */
    public static boolean shouldClose(boolean hardFarewell, boolean softComplete, boolean allQuestionsDone) {
        if (hardFarewell) {
            return true;
        }
        return softComplete && allQuestionsDone;
    }

    /**
     * 收尾持久锁生效时，任何输入只获得极短对等道别。
     */
    public static boolean lockedReply(boolean closingLocked) {
        return closingLocked;
    }

    /** 极短对等道别文案（代码给定，不走模型）。 */
    public static String lockedReplyText() {
        return "好的，今天非常感谢您的时间，先聊到这里，祝您顺利。";
    }
}
