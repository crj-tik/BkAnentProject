package com.bkanent.interview.engine;

import java.util.List;
import java.util.Locale;

/**
 * 受访者信号识别：停止/纠错/重复抗议/困惑/悬尾（约 50 词表）。
 *
 * <p>纯函数。信号决定管线分支：停止→收尾；纠错→承认并按纠正后事实继续；
 * 重复抗议→道歉换向；困惑→换大白话重说；话没说完→静默等待。</p>
 */
public final class SignalDetector {

    private SignalDetector() {
    }

    /** 硬道别（约 30 词，无条件收尾）。 */
    static final List<String> HARD_FAREWELLS = List.of(
            "拜拜", "再见", "今天就这样", "就到这里", "不聊了", "到此为止",
            "没时间了", "先这样吧", "下次再聊", "回头再聊", "今天到这里",
            "bye", "goodbye", "结束吧", "不说了", "我要走了", "先走了", "赶时间", "还有事");

    /** 软完结（仅全部题问完才收尾）。 */
    static final List<String> SOFT_COMPLETES = List.of(
            "我说完了", "就这些", "没有了", "想不起来了", "就这么多", "都说了", "没什么了");

    /** 重复抗议（道歉换向）。 */
    static final List<String> REPEAT_PROTESTS = List.of(
            "你不是问过了吗", "问过了", "刚说过", "我刚才说了", "重复了", "怎么又问",
            "不是回答过了吗", "都说了几遍了", "你到底听没听", "老是问这个", "又来了");

    /** 困惑信号（换大白话重说）。 */
    static final List<String> CONFUSIONS = List.of(
            "什么意思", "没听懂", "不懂", "你再说一遍", "什么叫", "啥意思", "指什么", "不明白");

    /** 纠错信号（承认并按纠正后事实继续）。 */
    static final List<String> CORRECTIONS = List.of(
            "说错了", "不对", "不是这样的", "更正一下", "搞错了", "纠正一下", "其实不是");

    /** 悬尾词：≤36 字回答以这些词结尾 → 静默等待。 */
    static final List<String> TRAILING_WORDS = List.of(
            "然后", "后来", "接着", "而且", "就是", "还有", "当时", "结果", "因为", "所以", "但是");

    /** 单一话轮信号集。 */
    public record Signals(
            boolean hardFarewell,
            boolean softComplete,
            boolean repeatProtest,
            boolean confusion,
            boolean correction,
            boolean trailingOff
    ) {
    }

    private static final int TRAILING_MAX_LEN = 36;

    public static Signals detect(String text) {
        String normalized = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return new Signals(false, false, false, false, false, false);
        }
        boolean hard = HARD_FAREWELLS.stream().anyMatch(normalized::contains);
        boolean soft = !hard && SOFT_COMPLETES.stream().anyMatch(normalized::contains);
        boolean protest = REPEAT_PROTESTS.stream().anyMatch(normalized::contains);
        boolean confused = CONFUSIONS.stream().anyMatch(normalized::contains);
        boolean correction = CORRECTIONS.stream().anyMatch(normalized::contains);
        boolean trailing = !hard && normalized.length() <= TRAILING_MAX_LEN
                && TRAILING_WORDS.stream().anyMatch(normalized::endsWith);
        return new Signals(hard, soft, protest, confused, correction, trailing);
    }
}
