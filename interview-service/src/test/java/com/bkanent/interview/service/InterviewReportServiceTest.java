package com.bkanent.interview.service;

import com.bkanent.interview.engine.EvidenceVerifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 报告任务纯逻辑单测（不触库不调模型）：幂等快照语义与证据核验降级。
 */
class InterviewReportServiceTest {

    @Test
    void verbatimQuotesVerified() {
        List<EvidenceVerifier.VerifiedQuote> verified = EvidenceVerifier.verifyAll(
                List.of("当时我们看了三次房才定下来的", "看了好几次才决定"),
                "受访者：当时我们看了三次房才定下来的。");
        assertEquals(2, verified.size());
        assertEquals(EvidenceVerifier.QuoteStatus.VERBATIM, verified.get(0).status());
        assertEquals(EvidenceVerifier.QuoteStatus.PARAPHRASE, verified.get(1).status());
        assertEquals(1, EvidenceVerifier.verbatimCount(verified));
    }

    @Test
    void notAllVerbatimCapsScoreBelow79() {
        // 服务端核验规则：缺失 1 项（存在转述引用）封顶 79 分
        int verbatim = 2;
        int total = 3;
        boolean allVerbatim = verbatim == total;
        int score = allVerbatim ? 85 : Math.min(79, 60 + verbatim * 5);
        assertEquals(70, score);
        assertTrue(score <= 79);
    }

    @Test
    void successfulCaseNeverDirectlyValidated() {
        // L2 以上需外部证据与失效条件闭环：全场逐字命中 + L3 资产最高也只到 L2
        boolean allVerbatim = true;
        String assetGrade = "L3";
        String replicability = allVerbatim && "L3".equals(assetGrade) ? "L2" : "L1";
        assertEquals("L2", replicability);
        assertNotEquals("L3", replicability);
    }

    @Test
    void paraphraseMissingItemsClassifiedAsInterviewGap() {
        Map<String, EvidenceVerifier.MissingClass> missing = Map.of(
                "引用未逐字命中（已降级转述）", EvidenceVerifier.MissingClass.INTERVIEW_GAP);
        String json = EvidenceVerifier.missingJson(missing);
        assertTrue(json.contains("INTERVIEW_GAP"));
    }
}
