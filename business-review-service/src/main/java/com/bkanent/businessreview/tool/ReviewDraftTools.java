package com.bkanent.businessreview.tool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Explicitly read-only drafting helper; it has no repository or source-system access. */
@Component
public class ReviewDraftTools {
    @Tool(description = "生成门店复盘的空白草稿结构。此工具不读取或修改业务状态；事实必须来自已授权上下文。")
    public Map<String, Object> draftReviewStructure(String focus) {
        String boundedFocus = focus == null ? "" : focus.strip();
        if (boundedFocus.length() > 300) boundedFocus = boundedFocus.substring(0, 300);
        return Map.of("artifactType", "store-business-review-draft", "focus", boundedFocus,
                "confirmedFacts", java.util.List.of(), "hypotheses", java.util.List.of(),
                "evidenceGaps", java.util.List.of(), "suggestedActions", java.util.List.of(),
                "dataMode", "UNKNOWN", "requiresUserReview", true, "writesBusinessState", false);
    }
}
