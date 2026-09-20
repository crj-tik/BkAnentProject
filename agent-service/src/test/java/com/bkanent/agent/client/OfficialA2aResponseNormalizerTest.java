package com.bkanent.agent.client;

import com.bkanent.agent.registry.AgentDescriptorSource;
import com.bkanent.agent.registry.AgentRuntimeType;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.spec.DataPart;
import io.a2a.spec.Part;
import io.a2a.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OfficialA2aResponseNormalizerTest {

    private final OfficialA2aResponseNormalizer normalizer =
            new OfficialA2aResponseNormalizer(new ObjectMapper());

    @Test
    void expandsStructuredJsonTextAndPreservesRemoteCorrelation() {
        AgentTaskInvokeResponse response = normalizer.normalize(
                descriptor(),
                request(),
                "```json\n{\"contentType\":\"listing\",\"listingCount\":2,"
                        + "\"nextHints\":[\"marketing.publish_prepare\"]}\n```",
                "COMPLETED",
                "remote-task-1",
                List.of("artifact-1", "artifact-1", "artifact-2"),
                null,
                List.of(new TextPart("```json\n{\"contentType\":\"listing\"}\n```")));

        assertThat(response.taskId()).isEqualTo("task-1");
        assertThat(response.structuredOutput())
                .containsEntry("contentType", "listing")
                .containsEntry("listingCount", 2)
                .containsEntry("remoteTaskId", "remote-task-1")
                .containsEntry("output", "```json\n{\"contentType\":\"listing\",\"listingCount\":2,"
                        + "\"nextHints\":[\"marketing.publish_prepare\"]}\n```");
        assertThat(response.nextHints()).containsExactly("marketing.publish_prepare");
        assertThat(response.artifactIds()).containsExactly("artifact-1", "artifact-2");
    }

    @Test
    void prefersDataPartAndKeepsPlainTextFallback() {
        List<Part<?>> parts = List.of(
                new TextPart("not-json text"),
                new DataPart(Map.of(
                        "contentType", "trade",
                        "tradeDecision", "PASS",
                        "nextHints", List.of("contract.create")))
        );

        AgentTaskInvokeResponse response = normalizer.normalize(
                descriptor(), request(), "not-json text", "COMPLETED", "remote-task-2",
                Set.of("artifact-data"), null, parts);

        assertThat(response.structuredOutput())
                .containsEntry("contentType", "trade")
                .containsEntry("tradeDecision", "PASS")
                .containsEntry("output", "not-json text");
        assertThat(response.nextHints()).containsExactly("contract.create");
        assertThat(response.summary()).isEqualTo("not-json text");
    }

    @Test
    void keepsPlainTextAndNormalizesFailureWithoutThrowing() {
        AgentTaskInvokeResponse response = normalizer.normalize(
                descriptor(), request(), "child agent failed", "FAILED", "remote-task-3",
                List.of("artifact-error"), "validation failed", List.of());

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.summary()).isEqualTo("child agent failed");
        assertThat(response.structuredOutput())
                .containsEntry("output", "child agent failed")
                .containsEntry("error", "validation failed")
                .containsEntry("remoteTaskId", "remote-task-3");
        assertThat(response.nextHints()).isEmpty();
    }

    @Test
    void ignoresInvalidOptionalFieldTypesWithoutDroppingTheResponse() {
        AgentTaskInvokeResponse response = normalizer.normalize(
                descriptor(), request(), "{\"contentType\":\"listing\",\"nextHints\":{\"agent\":true}}",
                "COMPLETED", "remote-task-4", List.of(), null, List.of());

        assertThat(response.structuredOutput()).containsEntry("contentType", "listing");
        assertThat(response.nextHints()).isEmpty();
        assertThat(response.status()).isEqualTo("COMPLETED");
    }

    private RegisteredAgentDescriptor descriptor() {
        return new RegisteredAgentDescriptor(
                "listing-agent", "http://127.0.0.1:9999", "/.well-known/agent.json", "/a2a",
                AgentRuntimeType.ALIBABA_A2A, AgentDescriptorSource.DISCOVERED_CARD,
                new AgentCard("listing-agent", "listing-agent", "listing", "1.0.0", List.of(),
                        List.of("listing"), true, true, "http://127.0.0.1:9999/a2a",
                        List.of("text"), List.of("text", "application/json")));
    }

    private AgentTaskInvokeRequest request() {
        return new AgentTaskInvokeRequest(
                "session-1", "task-1", "parent-1", "trace-1", "supervisor-agent", "listing-agent",
                "listing.search", "listing", "find listings", Map.of("keyword", "浦东"),
                List.of(), List.of(), "json", "idem-1", true);
    }
}
