package com.bkanent.interview.engine;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 出模质量门：违禁问句正则族拦截 + 笑声词/通用夸奖前缀剥离 + 160 字截断。
 *
 * <p>访中只还原故事、方法论全部留访后：策略提炼类问句在访中被全局禁止。
 * 拦截发生在模型输出之后、受访者看到之前，可拦截可截断。</p>
 */
public final class ProhibitedQuestionFilter {

    private ProhibitedQuestionFilter() {
    }

    /** 出模硬截断：4 句 / 160 字。 */
    public static final int MAX_OUTPUT_CHARS = 160;
    public static final int MAX_OUTPUT_SENTENCES = 4;

    /** 违禁问句族（约 15 条全局禁止）。 */
    private static final List<Pattern> PROHIBITED = List.of(
            // 反事实假设题
            Pattern.compile("如果当初.*(就|会|能)"),
            Pattern.compile("要是.*(的话)?[，,].*(会|就)"),
            // 策略提炼题（访中不要求总结方法论）
            Pattern.compile("总结一下|方法论|可复制|可以复用|沉淀下来|经验是什么|打法|套路"),
            // 逐字复述压力题
            Pattern.compile("复述|原话再说|重复一遍刚才"),
            // 微表情题
            Pattern.compile("表情|眼神|微表情|皱眉|嘴角"),
            // 诱导性预设
            Pattern.compile("突然拍板|最感动|要黄了|最自豪|最纠结的时刻"),
            // 换人/复制类
            Pattern.compile("换(一个|个)?(店长|经理|新人)|你不在了|交给新人|可复制到|别的团队也能"),
            // 角色越界（客户成交人被问业主侧动作）
            Pattern.compile("业主(当时|是).*(决定|拍板|挂牌价)"),
            // 风险主题诱导
            Pattern.compile("违规|灰色手段|飞单|私下返佣|避税")
    );

    /** 笑声词剥离。 */
    private static final Pattern LAUGHTER = Pattern.compile("[哈嘿呵嘻]{2,}");

    /** 通用夸奖前缀剥离。 */
    private static final List<Pattern> PRAISE_PREFIXES = List.of(
            Pattern.compile("^非常?(好|棒|专业)[的啦]?\\s*[，,]?"),
            Pattern.compile("^说得(很)?好\\s*[，,]?"),
            Pattern.compile("^这个(回答|说法)(很)?(好|到位)\\s*[，,]?")
    );

    /** 质量门结果。 */
    public record GateResult(boolean blocked, String reason, String text) {
        static GateResult pass(String text) {
            return new GateResult(false, null, text);
        }
    }

    /**
     * 对模型造句输出执行播出前质量门。
     */
    public static GateResult apply(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            return new GateResult(true, "EMPTY", modelOutput);
        }
        String text = LAUGHTER.matcher(modelOutput).replaceAll("");
        for (Pattern praise : PRAISE_PREFIXES) {
            text = praise.matcher(text).replaceFirst("");
        }
        for (Pattern prohibited : PROHIBITED) {
            if (prohibited.matcher(text).find()) {
                return new GateResult(true, "PROHIBITED", text);
            }
        }
        String truncated = truncate(text);
        return GateResult.pass(truncated);
    }

    /** 4 句 / 160 字硬截断。 */
    static String truncate(String text) {
        String normalized = text.trim();
        if (normalized.length() <= MAX_OUTPUT_CHARS) {
            return cutSentences(normalized);
        }
        String cut = normalized.substring(0, MAX_OUTPUT_CHARS);
        int lastPunct = Math.max(
                Math.max(cut.lastIndexOf('。'), cut.lastIndexOf('！')),
                Math.max(cut.lastIndexOf('？'), cut.lastIndexOf('，')));
        return cutSentences(lastPunct > 20 ? cut.substring(0, lastPunct + 1) : cut);
    }

    private static String cutSentences(String text) {
        String[] sentences = text.split("(?<=[。！？])");
        if (sentences.length <= MAX_OUTPUT_SENTENCES) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_OUTPUT_SENTENCES; i++) {
            sb.append(sentences[i]);
        }
        return sb.toString();
    }
}
