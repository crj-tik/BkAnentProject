package com.bkanent.common.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentOutputSanitizerTest {

    @Test
    void removesPrivateFieldsAndRedactsCredentialValuesRecursively() {
        Map<String, Object> sanitized = AgentOutputSanitizer.sanitizeMap(Map.of(
                "publicResult", Map.of(
                        "status", "verified",
                        "authBeacon", "private-beacon-value",
                        "desensitizationMap", Map.of("name", "private-name"),
                        "reasoning", "private thought",
                        "note", "Bearer fake-development-token"
                ),
                "systemPrompt", "private prompt"
        ));

        assertThat(sanitized).doesNotContainKey("systemPrompt");
        assertThat(sanitized.get("publicResult").toString())
                .contains("verified", "已隐藏敏感内容")
                .doesNotContain("private-beacon-value", "private-name", "private thought", "fake-development-token");
    }

    @Test
    void detectsCredentialsAndPrivateMappingsInFreeText() {
        assertThat(AgentOutputSanitizer.sanitizeText("结果 access_token=private-token"))
                .isEqualTo("[已隐藏敏感内容]");
        assertThat(AgentOutputSanitizer.sanitizeText("desensitization_map: private mappings"))
                .isEqualTo("[已隐藏敏感内容]");
    }
}
