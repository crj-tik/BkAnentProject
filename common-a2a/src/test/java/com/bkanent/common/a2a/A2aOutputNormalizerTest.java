package com.bkanent.common.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aOutputNormalizerTest {

    private final A2aOutputNormalizer normalizer = new A2aOutputNormalizer(new ObjectMapper());
    private final A2aOutputPolicy policy = A2aOutputPolicy.structured("listing");

    @Test
    void normalizesCodeFenceAndCompletesCommonEnvelope() {
        A2aOutput output = normalizer.normalize(
                "```json\n{\"listingCount\":2,\"nextHints\":\"marketing.publish_prepare\","
                        + "\"reasoning\":\"internal\",\"apiKey\":\"secret\"}\n```",
                policy,
                true);

        assertThat(output.structured()).isTrue();
        assertThat(output.data()).containsEntry("contentType", "listing")
                .containsEntry("listingCount", 2)
                .containsEntry("summary", "listing result completed")
                .containsEntry("nextHints", java.util.List.of("marketing.publish_prepare"));
        assertThat(output.data()).doesNotContainKeys("reasoning", "apiKey");
        assertThat(output.text()).doesNotContain("```");
    }

    @Test
    void preservesDomainFieldsAndDeduplicatesNextHints() {
        A2aOutput output = normalizer.normalize(
                "{\"contentType\":\"trade\",\"summary\":\"可行\","
                        + "\"nextHints\":[\"contract.review\",\"contract.review\"],"
                        + "\"riskLevel\":\"LOW\"}",
                A2aOutputPolicy.structured("trade"),
                true);

        assertThat(output.data()).containsEntry("riskLevel", "LOW")
                .containsEntry("summary", "可行")
                .containsEntry("nextHints", java.util.List.of("contract.review"));
    }

    @Test
    void rejectsNonObjectStructuredOutput() {
        assertThatThrownBy(() -> normalizer.normalize("[1,2,3]", policy, true))
                .isInstanceOf(A2aOutputException.class)
                .hasMessageContaining("JSON object");
    }

    @Test
    void preservesExplicitTextOutput() {
        A2aOutput output = normalizer.normalize("这是文本结果", policy, false);

        assertThat(output.structured()).isFalse();
        assertThat(output.outputMode()).isEqualTo(A2aOutputPolicy.TEXT_MODE);
        assertThat(output.text()).isEqualTo("这是文本结果");
    }

    @Test
    void keepsNullableDomainFieldsWithoutBreakingCanonicalization() {
        A2aOutput output = normalizer.normalize(
                "{\"decision\":\"NO_RESULT\",\"topPick\":null}", policy, true);

        assertThat(output.data()).containsEntry("topPick", null)
                .containsEntry("contentType", "listing");
        assertThat(output.text()).contains("topPick");
    }
}
