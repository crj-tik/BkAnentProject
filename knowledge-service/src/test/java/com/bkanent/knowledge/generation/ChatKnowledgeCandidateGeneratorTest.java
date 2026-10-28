package com.bkanent.knowledge.generation;

import com.bkanent.knowledge.source.InterviewSourceSnapshot;
import com.bkanent.knowledge.source.SourceBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ChatKnowledgeCandidateGeneratorTest {
    private final ChatKnowledgeCandidateGenerator generator =
            new ChatKnowledgeCandidateGenerator(mock(ChatModel.class), new ObjectMapper());

    @Test
    void candidateMustHaveAtLeastOneVerifiableSourceEvidence() {
        String candidate = """
                {
                  "problem":"客戶反饋滯後",
                  "scene":"帶看",
                  "preconditions":["已完成帶看"],
                  "actions":["約定回訪時間"],
                  "observedResults":["實際結果待核實"],
                  "applicability":["客戶需要時間考慮"],
                  "unverifiedItems":["是否提高成交率"],
                  "evidenceLevel":"VERIFIED",
                  "summary":"按約定時間跟進",
                  "evidence":[]
                }
                """;

        assertThatThrownBy(() -> generator.parseAndValidate(candidate, List.of(source("1001"))))
                .isInstanceOfSatisfying(IllegalArgumentException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.getMessage())
                                .isEqualTo("INVALID_GENERATION_EVIDENCE"));
    }

    @Test
    void candidateEvidenceMustBeUniquePerSourceBecauseRevisionStoresOneStableRefPerAsset() {
        String candidate = """
                {
                  "problem":"客戶反饋滯後",
                  "scene":"帶看",
                  "preconditions":["已完成帶看"],
                  "actions":["約定回訪時間"],
                  "observedResults":["實際結果待核實"],
                  "applicability":["客戶需要時間考慮"],
                  "unverifiedItems":["是否提高成交率"],
                  "evidenceLevel":"VERIFIED",
                  "summary":"按約定時間跟進",
                  "evidence":[
                    {"assetId":"1001","quote":"客户说需要考虑"},
                    {"assetId":"1001","quote":"客户说需要考虑"}
                  ]
                }
                """;

        assertThatThrownBy(() -> generator.parseAndValidate(candidate,
                List.of(source("1001"), source("1002"))))
                .isInstanceOfSatisfying(IllegalArgumentException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.getMessage())
                                .isEqualTo("INVALID_GENERATION_EVIDENCE"));
    }

    private static InterviewSourceSnapshot source(String assetId) {
        String content = "受访者：客户说需要考虑。";
        return new InterviewSourceSnapshot(new SourceBinding(assetId, "a".repeat(64), "b".repeat(64),
                1, 1, 1, "UNKNOWN"), "带看复盘", content, "L2", "UPLOAD");
    }
}
