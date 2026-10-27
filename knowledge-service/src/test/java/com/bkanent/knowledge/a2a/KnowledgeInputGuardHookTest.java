package com.bkanent.knowledge.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeInputGuardHookTest {
    private final KnowledgeInputGuardHook hook = new KnowledgeInputGuardHook(new ObjectMapper());

    @Test
    void acceptsOnlyReferenceShapedGenerationAndStatusRequests() {
        assertThatCode(() -> hook.validateUserText("""
                [Structured A2A input] {"operation":"GENERATE","knowledgeId":"2f1e0c39-9e7c-4a84-8b7e-8a5943ce2c7d",
                "requestId":"req-1","expectedVersion":2,"sourceAssetIds":["101"],"rulesVersion":"rules-v1"}
                """.replace("\n", " "))).doesNotThrowAnyException();
        assertThatCode(() -> hook.validateUserText("""
                {"operation":"STATUS","knowledgeId":"2f1e0c39-9e7c-4a84-8b7e-8a5943ce2c7d",
                "taskId":"2f1e0c39-9e7c-4a84-8b7e-8a5943ce2c7d"}
                """.replace("\n", " "))).doesNotThrowAnyException();
    }

    @Test
    void rejectsTranscriptBodyQuotesAndUnstructuredSentinelBeforeMemoryHookRuns() {
        assertRejected("{\"operation\":\"GENERATE\",\"knowledgeId\":\"2f1e0c39-9e7c-4a84-8b7e-8a5943ce2c7d\","
                + "\"requestId\":\"req-1\",\"expectedVersion\":2,\"sourceAssetIds\":[\"101\"],"
                + "\"rulesVersion\":\"rules-v1\",\"body\":\"哨兵逐字稿原文\"}");
        assertRejected("{\"operation\":\"GENERATE\",\"knowledgeId\":\"2f1e0c39-9e7c-4a84-8b7e-8a5943ce2c7d\","
                + "\"requestId\":\"req-1\",\"expectedVersion\":2,\"sourceAssetIds\":[\"101\"],"
                + "\"rulesVersion\":\"rules-v1\",\"quote\":\"哨兵逐字稿原文\"}");
        assertRejected("哨兵逐字稿原文");
    }

    private void assertRejected(String text) {
        assertThatThrownBy(() -> hook.validateUserText(text))
                .isInstanceOf(com.bkanent.common.rag.RagException.class);
    }
}
