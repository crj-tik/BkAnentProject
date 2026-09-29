package com.bkanent.interview.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * L0–L3 归档评级：确定性规则，不用模型。
 *
 * <p>六项检查：正文≥300 字 / 对象明确 / 主题聚焦≥700 字 / 模块闭环 /
 * 证据充分 / 话轮≥4。L0 拒收、L1 待补充、L2 正式归档、L3 标杆归档
 * （≥1500 字 + 话轮≥10 + 模范线）。元数据缺失强制降级；补齐后按同一
 * 规则重评级。</p>
 */
public final class ArchiveGrader {

    private ArchiveGrader() {
    }

    public static final int L0_MIN_WORDS = 300;
    public static final int L2_TOPIC_FOCUS_WORDS = 700;
    public static final int L2_MIN_TURNS = 4;
    public static final int L3_MIN_WORDS = 1500;
    public static final int L3_MIN_TURNS = 10;

    /** 六项检查项。 */
    public record GradeCheck(String item, boolean passed, String basis) {
    }

    /** 评级结果。 */
    public record GradeResult(String grade, List<GradeCheck> checks) {
    }

    /**
     * 确定性评级。
     *
     * @param wordCount     正文字数（脱敏域）
     * @param subjectClear  对象明确（受访者角色与主题已识别）
     * @param topicFocused  主题聚焦（≥700 字实质内容）
     * @param moduleClosed  模块闭环（提纲各模块均有覆盖）
     * @param evidenceSufficient 证据充分（含可核验原声）
     * @param turnCount     话轮数
     * @param metadataComplete 元数据完整（缺失强制降一级）
     */
    public static GradeResult grade(int wordCount, boolean subjectClear, boolean topicFocused,
                                    boolean moduleClosed, boolean evidenceSufficient,
                                    int turnCount, boolean metadataComplete) {
        List<GradeCheck> checks = new ArrayList<>();
        checks.add(new GradeCheck("body_length", wordCount >= L0_MIN_WORDS,
                "wordCount=" + wordCount + " (min " + L0_MIN_WORDS + ")"));
        checks.add(new GradeCheck("subject_clear", subjectClear, "subject identified"));
        checks.add(new GradeCheck("topic_focus", topicFocused && wordCount >= L2_TOPIC_FOCUS_WORDS,
                "wordCount=" + wordCount + " (focus min " + L2_TOPIC_FOCUS_WORDS + ")"));
        checks.add(new GradeCheck("module_closed", moduleClosed, "outline modules covered"));
        checks.add(new GradeCheck("evidence_sufficient", evidenceSufficient, "verifiable quotes present"));
        checks.add(new GradeCheck("turn_count", turnCount >= L2_MIN_TURNS,
                "turnCount=" + turnCount + " (min " + L2_MIN_TURNS + ")"));

        long passed = checks.stream().filter(GradeCheck::passed).count();

        String grade;
        if (!checks.get(0).passed()) {
            grade = "L0";
        } else if (passed < 5) {
            grade = "L1";
        } else if (passed == 5) {
            grade = metadataComplete ? "L1" : "L1";
        } else if (wordCount >= L3_MIN_WORDS && turnCount >= L3_MIN_TURNS
                && moduleClosed && evidenceSufficient && metadataComplete) {
            grade = "L3";
        } else {
            grade = metadataComplete ? "L2" : "L1";
        }
        if (!metadataComplete && ("L2".equals(grade) || "L3".equals(grade))) {
            grade = "L1";
        }
        return new GradeResult(grade, checks);
    }

    /** 评级依据 JSON（落 grade_basis）。 */
    public static String gradeBasisJson(GradeResult result) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"grade\":\"").append(result.grade()).append("\",\"checks\":[");
        for (int i = 0; i < result.checks().size(); i++) {
            GradeCheck c = result.checks().get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"item\":\"").append(c.item()).append("\",\"passed\":").append(c.passed())
                    .append(",\"basis\":\"").append(c.basis().replace("\"", "'")).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** MCP/检索可见等级。 */
    public static boolean isPubliclyVisible(String grade) {
        return "L2".equals(grade) || "L3".equals(grade);
    }
}
