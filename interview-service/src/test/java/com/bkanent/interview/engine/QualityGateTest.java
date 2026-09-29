package com.bkanent.interview.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QualityGateTest {

    @Test
    void phoneSanitizedBeforeModel() {
        Sanitizer.SanitizeResult r = Sanitizer.sanitize("他打电话13812345678说价格谈到350万");
        assertFalse(r.sanitizedText().contains("13812345678"));
        assertFalse(r.sanitizedText().contains("350万"));
        assertTrue(r.sanitizedText().contains("[手机号0]"));
        assertTrue(r.placeholderMap().containsKey("[手机号0]"));
        assertTrue(r.placeholderMap().containsValue("13812345678"));
    }

    @Test
    void addressSanitized() {
        Sanitizer.SanitizeResult r = Sanitizer.sanitize("房子在广州市天河区体育西路12号的小区");
        assertFalse(r.sanitizedText().contains("体育西路"));
    }

    @Test
    void normalTextUntouched() {
        Sanitizer.SanitizeResult r = Sanitizer.sanitize("当时我们就是觉得户型不错");
        assertEquals("当时我们就是觉得户型不错", r.sanitizedText());
        assertTrue(r.placeholderMap().isEmpty());
    }

    @Test
    void rephrasedRepeatBlocked() {
        assertTrue(RepetitionGuard.isRephrasedRepeat(
                "当时家里是谁做的购房决定",
                List.of("当时家里是谁做的买房决定")));
    }

    @Test
    void distinctQuestionPasses() {
        assertFalse(RepetitionGuard.isRephrasedRepeat(
                "看房那天你们先看了哪个小区",
                List.of("当时家里是谁做的购房决定", "签约当天业主的态度怎么样")));
    }

    @Test
    void emptyOrNullSafe() {
        assertFalse(RepetitionGuard.isRephrasedRepeat(null, List.of("a")));
        assertFalse(RepetitionGuard.isRephrasedRepeat("问题", null));
    }

    @Test
    void strategyQuestionProhibited() {
        ProhibitedQuestionFilter.GateResult r = ProhibitedQuestionFilter.apply(
                "这个打法可以沉淀下来吗");
        assertTrue(r.blocked());
        assertEquals("PROHIBITED", r.reason());
    }

    @Test
    void counterfactualProhibited() {
        assertTrue(ProhibitedQuestionFilter.apply("如果当初价格再低一点你会买吗").blocked());
    }

    @Test
    void replacementProhibited() {
        assertTrue(ProhibitedQuestionFilter.apply("这套经验可以复制到别的团队也能用吗").blocked());
    }

    @Test
    void laughterStrippedAndNormalQuestionPasses() {
        ProhibitedQuestionFilter.GateResult r = ProhibitedQuestionFilter.apply(
                "哈哈那后来你们是怎么谈价的呢");
        assertFalse(r.blocked());
        assertFalse(r.text().contains("哈哈"));
        assertTrue(r.text().contains("谈价"));
    }

    @Test
    void longOutputTruncatedTo160() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("当时的情况大概是这样的我们聊了很久，");
        }
        ProhibitedQuestionFilter.GateResult r = ProhibitedQuestionFilter.apply(sb.toString());
        assertTrue(r.text().length() <= ProhibitedQuestionFilter.MAX_OUTPUT_CHARS + 2);
    }

    @Test
    void answerStatusJudgedByCode() {
        assertTrue(AnswerStatusJudge.shouldMarkAnswered(ProbeDecisionEngine.Move.ADVANCE, 1));
        assertTrue(AnswerStatusJudge.shouldMarkAnswered(ProbeDecisionEngine.Move.CLOSE, 2));
        assertFalse(AnswerStatusJudge.shouldMarkAnswered(ProbeDecisionEngine.Move.OPEN_DRILL, 1));
        assertTrue(AnswerStatusJudge.shouldMarkAnswered(ProbeDecisionEngine.Move.OPEN_DRILL, 3));
    }

    @Test
    void verbatimQuoteVerifiedInSanitizedDomain() {
        String asset = "李女士：当时我们看了三次房才定下来的。";
        EvidenceVerifier.VerifiedQuote v = EvidenceVerifier.verify("当时我们看了三次房才定下来的", asset);
        assertEquals(EvidenceVerifier.QuoteStatus.VERBATIM, v.status());
    }

    @Test
    void paraphraseQuoteDegrades() {
        EvidenceVerifier.VerifiedQuote v = EvidenceVerifier.verify("看了好几次房才决定", "李女士：当时我们看了三次房才定下来的。");
        assertEquals(EvidenceVerifier.QuoteStatus.PARAPHRASE, v.status());
    }

    @Test
    void missingItemsClassified() {
        assertEquals(EvidenceVerifier.MissingClass.EXTERNAL_VERIFICATION,
                EvidenceVerifier.classifyMissing("需要竞品成交记录外部核验"));
        assertEquals(EvidenceVerifier.MissingClass.FUTURE_RESEARCH,
                EvidenceVerifier.classifyMissing("需要长期跟踪后续复盘"));
        assertEquals(EvidenceVerifier.MissingClass.INTERVIEW_GAP,
                EvidenceVerifier.classifyMissing("业主决策过程未覆盖"));
    }

    @Test
    void missingJsonGroupsByClass() {
        String json = EvidenceVerifier.missingJson(Map.of(
                "业主决策过程未覆盖", EvidenceVerifier.MissingClass.INTERVIEW_GAP));
        assertTrue(json.contains("INTERVIEW_GAP"));
        assertTrue(json.contains("业主决策过程未覆盖"));
    }
}
