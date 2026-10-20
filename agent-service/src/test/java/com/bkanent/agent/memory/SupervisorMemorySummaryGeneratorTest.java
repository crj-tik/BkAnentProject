package com.bkanent.agent.memory;

import com.bkanent.agent.orchestration.*;
import com.bkanent.common.memory.*;
import com.bkanent.memory.runtime.MemoryCompactionCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupervisorMemorySummaryGeneratorTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final SupervisorModelTurn model = mock(SupervisorModelTurn.class);
    final SupervisorMemoryProjection projection = new SupervisorMemoryProjection(mapper);
    final MemoryContextKey context = new MemoryContextKey("owner", "supervisor", "supervisor-agent", "session");
    final SupervisorMemorySummaryGenerator generator = new SupervisorMemorySummaryGenerator(model, mapper, 30000, 256);
    MemoryMessage user(String text) { return projection.project(context, "run", "a".repeat(64), 1, StoredModelMessage.user(text)); }
    MemoryCompactionCoordinator.Input input(MemoryMessage message, List<MemorySummary> summaries) {
        Set<MemorySourceDependency> dependencies = new LinkedHashSet<>();
        summaries.forEach(s -> dependencies.addAll(s.dependencies())); dependencies.add(new MemorySourceDependency(message.source(), null));
        return new MemoryCompactionCoordinator.Input(new MemoryWorkingSetResponse(context, new MemoryConversationVersion(2, 2, "v2"),
                List.of(message), summaries, List.of()), message.sequence(), List.of(message), List.copyOf(dependencies), List.of(), MemoryCompactionCoordinator.Trigger.TOKEN_PRESSURE);
    }
    String output(String entities) { return "{\"items\":[{\"kind\":\"NEGATION\",\"text\":\"不要猜测任务成功\",\"evidence\":[0]}],\"entities\":" + entities + "}"; }
    void reply(String entities) { when(model.call(anyList(), anyList())).thenReturn(new AssistantMessage(output(entities))); }
    @Test void createsGroundedVersionedEntityWithExactEvidenceAndNoToolExecution() {
        reply("[{\"previousEntity\":null,\"aliases\":[\"房源甲\"],\"briefDefinition\":\"用户明确排除的房源\",\"evidence\":[0]}]");
        var message = user("不要房源甲"); var candidate = generator.generate(input(message, List.of()));
        var entity = candidate.entities().get(0);
        assertEquals(List.of("房源甲"), entity.aliases());
        assertEquals(1, entity.version().revision());
        assertEquals(MemoryCanonicalHash.entityHash(entity), entity.version().contentHash());
        assertEquals(List.of(new MemorySourceDependency(message.source(), null)), entity.dependencies());
        assertEquals(MemorySummary.ItemKind.NEGATION, candidate.items().get(0).kind());
        verify(model).call(anyList(), eq(List.<ToolCallback>of()));
    }
    @Test void sameAliasFromDifferentFrozenRevisionsDoesNotMergeIdentity() {
        reply("[{\"previousEntity\":null,\"aliases\":[\"房源甲\"],\"briefDefinition\":\"资料中的实体\",\"evidence\":[0]}]");
        var first = generator.generate(input(user("房源甲"), List.of())).entities().get(0);
        var frozen = input(user("房源甲"), List.of());
        var changed = new MemoryCompactionCoordinator.Input(new MemoryWorkingSetResponse(context, new MemoryConversationVersion(3, 2, "v2"),
                frozen.messages(), List.of(), List.of()), frozen.through(), frozen.messages(), frozen.dependencies(), List.of(), frozen.trigger());
        assertNotEquals(first.entityId(), generator.generate(changed).entities().get(0).entityId());
    }
    @Test void preservesOldEntityWhenOmittedAndUpdatesOnlyItsExistingAliases() {
        reply("[{\"previousEntity\":null,\"aliases\":[\"房源甲\"],\"briefDefinition\":\"排除对象\",\"evidence\":[0]}]");
        var message = user("排除房源甲"); var anchor = generator.generate(input(message, List.of())).entities().get(0);
        var dep = List.of(new MemorySourceDependency(message.source(), null));
        var summary = new MemorySummary("summary", context, message.association(), new MemoryRevision(1, "0".repeat(64), "v2"), 1, null,
                new MemoryCoveredRange(1, 1), List.of(), List.of(new MemorySummary.Item(MemorySummary.ItemKind.NEGATION, "排除", dep)), List.of(anchor), dep);
        reply("[]"); assertEquals(List.of(anchor), generator.generate(input(user("继续"), List.of(summary))).entities());
        reply("[{\"previousEntity\":0,\"aliases\":[\"房源甲\"],\"briefDefinition\":\"保留否定\",\"evidence\":[1]}]");
        var updated = generator.generate(input(user("仍然排除"), List.of(summary))).entities().get(0);
        assertEquals(anchor.entityId(), updated.entityId()); assertEquals(2, updated.version().revision());
        assertEquals(2, updated.dependencies().size()); assertEquals(MemoryCanonicalHash.entityHash(updated), updated.version().contentHash());
        reply("[{\"previousEntity\":0,\"aliases\":[\"房源乙\"],\"briefDefinition\":\"合并\",\"evidence\":[1]}]");
        invalid(() -> generator.generate(input(user("房源乙"), List.of(summary))));
    }
    @Test void rejectsUngroundedAliasesAssistantOnlyEvidenceBadIndicesDuplicateAliasesAndCredentialText() {
        var message = user("房源甲");
        for (String entities : List.of(
                "[{\"previousEntity\":null,\"aliases\":[\"房源乙\"],\"briefDefinition\":\"对象\",\"evidence\":[0]}]",
                "[{\"previousEntity\":null,\"aliases\":[\"房源甲\",\"房源甲\"],\"briefDefinition\":\"对象\",\"evidence\":[0]}]",
                "[{\"previousEntity\":null,\"aliases\":[\"房源甲\"],\"briefDefinition\":\"对象\",\"evidence\":[99]}]",
                "[{\"previousEntity\":null,\"aliases\":[\"房源甲\"],\"briefDefinition\":\"password=forbidden\",\"evidence\":[0]}]")) {
            reply(entities); invalid(() -> generator.generate(input(message, List.of())));
        }
        reply("[{\"previousEntity\":null,\"aliases\":[\"房源甲\"],\"briefDefinition\":\"对象\",\"evidence\":[0]}]");
        var assistant = projection.project(context, "run", "b".repeat(64), 1, StoredModelMessage.assistant(new AssistantMessage("房源甲")));
        invalid(() -> generator.generate(input(assistant, List.of())));
    }
    @Test void largeToolPayloadIsReferencedInSummaryPromptAndItsExecutionStateIsPreserved() throws Exception {
        String content = "unseen-detail-".repeat(1000);
        var message = projection.project(context, "run", "c".repeat(64), 1, StoredModelMessage.tools(List.of(
                new ToolResponseMessage.ToolResponse("call", "find", mapper.writeValueAsString(Map.of("detail", content, "status", "PARTIAL"))))));
        when(model.call(anyList(), anyList())).thenAnswer(call -> {
            List<Message> messages = call.getArgument(0);
            assertFalse(messages.toString().contains(content)); assertTrue(messages.toString().contains("REFERENCED_COMPLETE"));
            return new AssistantMessage(output("[]"));
        });
        var candidate = generator.generate(input(message, List.of()));
        assertEquals(MemoryToolResponse.ExecutionState.PARTIAL, candidate.toolResults().get(0).state());
        assertEquals(message.source(), candidate.toolResults().get(0).source());
    }
    @Test void invalidShapeOrSystemOverheadStopsBeforeAdoption() {
        when(model.call(anyList(), anyList())).thenReturn(new AssistantMessage("[]"));
        invalid(() -> generator.generate(input(user("fact"), List.of())));
        clearInvocations(model);
        var bounded = new SupervisorMemorySummaryGenerator(model, mapper, 400);
        assertEquals(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED, assertThrows(MemoryException.class, () -> bounded.generate(input(user("fact"), List.of()))).code());
        verifyNoInteractions(model);
    }
    void invalid(org.junit.jupiter.api.function.Executable action) { assertEquals(MemoryErrorCode.INVALID_EXTRACTION, assertThrows(MemoryException.class, action).code()); }
}
