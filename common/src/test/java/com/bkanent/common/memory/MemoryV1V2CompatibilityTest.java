package com.bkanent.common.memory;

import com.bkanent.common.agent.SessionMemoryUpsertRequest;
import com.bkanent.common.agent.UserPreferenceRecord;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1/v2 coexistence contract (task 2.5): the legacy REST surface and the v2 RPC surface
 * stay disjoint — v1 requests keep their old shapes untouched, no v2 write path may
 * silently dual-write legacy tables, and v1 shapes must not be mistaken for the v2
 * memory chain (full history, grants, compaction) they do not provide.
 */
class MemoryV1V2CompatibilityTest {

    @Test
    void v2InterfaceNeverTouchesLegacyRestPaths() {
        List<String> legacyPaths = List.of(
                "/internal/memory/sessions/upsert", "/internal/memory/sessions",
                "/internal/memory/artifacts", "/internal/memory/handoffs",
                "/internal/memory/user-preferences/upsert", "/internal/memory/system-constraints",
                "/internal/memory/rag/search");
        List<String> v2Operations = Arrays.stream(MemoryV2Contract.class.getDeclaredFields())
                .filter(field -> field.getName().startsWith("OPERATION_"))
                .map(field -> {
                    try { return (String) field.get(null); }
                    catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
                }).toList();
        assertThat(v2Operations).hasSize(14).allSatisfy(operation -> {
            assertThat(operation).startsWith("memory.");
            assertThat(legacyPaths).noneMatch(path -> operation.contains(path));
        });
    }

    @Test
    void legacySessionAndPreferenceShapesStayV1AndCarryNoV2Semantics() {
        // memory is a flat map and summary a plain overwrite field — the documented
        // v1 limitation, not a hidden PreferenceBlock/revision/trace chain.
        for (var component : SessionMemoryUpsertRequest.class.getRecordComponents()) {
            assertThat(component.getName()).isIn("sessionId", "userId", "memory", "summary", "traceId");
        }
        for (var component : UserPreferenceRecord.class.getRecordComponents()) {
            assertThat(component.getName()).doesNotContain("Revision", "Ordinal", "Grant", "BlockId", "Cursor");
        }
        assertThat(Arrays.stream(SessionMemoryUpsertRequest.class.getRecordComponents())
                .map(RecordComponent::getName)).doesNotContain("historyPage", "authorizationRevision", "coveredRange");
    }

    @Test
    void v2DtoFamilyAndV1FamilyShareNoWireType() {
        List<Class<?>> v1Types = List.of(SessionMemoryUpsertRequest.class,
                com.bkanent.common.agent.SessionMemoryResponse.class, UserPreferenceRecord.class);
        List<Class<?>> v2Types = List.of(MemoryRequestEnvelope.class, MemoryRequestProof.class, MemoryWorkingSetRequest.class,
                MemoryWorkingSetResponse.class, MemoryReceivePermitRequest.class, MemoryReceivePermitResponse.class,
                MemoryDeliveryRequest.class, MemoryDeliveryResponse.class, MemorySummaryRequest.class,
                MemorySummaryResponse.class, MemoryGrantRequest.class, MemoryGrantResponse.class);
        for (Class<?> v1 : v1Types) {
            for (Class<?> v2 : v2Types) {
                assertThat(v1).isNotEqualTo(v2);
                assertThat(v1.getPackageName() + "." + v1.getSimpleName())
                        .isNotEqualTo(v2.getPackageName() + "." + v2.getSimpleName());
            }
        }
        // v2 requests never embed a v1 type, so no v2 write can be routed into legacy shapes.
        for (Class<?> v2 : v2Types) {
            for (var component : v2.getRecordComponents()) {
                assertThat(component.getType().getPackageName()).doesNotStartWith("com.bkanent.common.agent");
            }
        }
    }
}
