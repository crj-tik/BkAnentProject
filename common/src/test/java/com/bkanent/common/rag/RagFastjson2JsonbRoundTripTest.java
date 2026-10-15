package com.bkanent.common.rag;

import com.alibaba.fastjson2.JSONB;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dubbo's fastjson2 transport reads RPC results through JSONB. fastjson2 2.0.52's JSONB
 * readInstant throws UnsupportedOperationException, which exhausted every memory outbox
 * retry even though the provider had applied the events (KI-113). This pins the JSONB
 * Instant round-trip of the status contract the dispatcher reads back.
 */
class RagFastjson2JsonbRoundTripTest {
    @Test
    void jsonbRoundTripPreservesRagIndexStatusWithInstant() {
        RagIndexStatus status = new RagIndexStatus(1, "req", "evt", "memory.constraint", "constraint:YQ",
                3, "a".repeat(64), RagIndexState.RETRY_PENDING, 3, false,
                new RagBackendStatus(0, 2, RagErrorCode.DEPENDENCY_UNAVAILABLE, Instant.parse("2026-10-15T00:00:00Z")),
                new RagBackendStatus(3, 0, null, null), RagErrorCode.DEPENDENCY_UNAVAILABLE);
        byte[] encoded = JSONB.toBytes(status);
        RagIndexStatus restored = JSONB.parseObject(encoded, RagIndexStatus.class);
        assertThat(restored).isEqualTo(status);
    }

    @Test
    void jsonbRoundTripHandlesNullInstantAndNestedEvents() {
        RagIndexEvent event = new RagIndexEvent(1, "r", "e", "memory.preference", "preference:YQ", 2,
                "memory-v1", RagIndexOperation.UPSERT, "b".repeat(64),
                new RagDocument("user_preference", "k", "v", java.util.Map.of()),
                Instant.parse("2026-10-14T00:00:00Z"));
        byte[] encoded = JSONB.toBytes(event);
        assertThat(JSONB.parseObject(encoded, RagIndexEvent.class)).isEqualTo(event);
    }

    @Test
    void jsonbRoundTripPreservesSearchRequestWithGenericListFields() {
        RagSearchRequest request = new RagSearchRequest(1, "r", "memory.constraint", "房源发布规范",
                List.of(new RagFilter("category", RagFilterOperator.IN,
                        RagValue.strings(List.of("compliance")))),
                null, null, null, null, null, null).normalized();
        byte[] encoded = JSONB.toBytes(request);
        assertThat(JSONB.parseObject(encoded, RagSearchRequest.class)).isEqualTo(request);
    }

    @Test
    void dubboWireFeatureCombinationRoundTripsSearchRequest() {
        RagSearchRequest request = new RagSearchRequest(1, "r", "memory.constraint", "房源发布规范",
                List.of(new RagFilter("category", RagFilterOperator.IN,
                        RagValue.strings(List.of("compliance")))),
                null, null, null, null, null, null).normalized();
        // Dubbo 3.2.15 FastJson2ObjectOutput writes with WriteClassName+FieldBased(+...) and the
        // matching input reads with SupportAutoType+FieldBased(+...): this is the actual RPC wire
        // combination, not plain JSONB.toBytes/parseObject.
        com.alibaba.fastjson2.JSONWriter.Feature[] write = {
                com.alibaba.fastjson2.JSONWriter.Feature.WriteClassName,
                com.alibaba.fastjson2.JSONWriter.Feature.FieldBased,
                com.alibaba.fastjson2.JSONWriter.Feature.ErrorOnNoneSerializable,
                com.alibaba.fastjson2.JSONWriter.Feature.ReferenceDetection,
                com.alibaba.fastjson2.JSONWriter.Feature.WriteNulls,
                com.alibaba.fastjson2.JSONWriter.Feature.NotWriteDefaultValue,
                com.alibaba.fastjson2.JSONWriter.Feature.NotWriteHashMapArrayListClassName,
                com.alibaba.fastjson2.JSONWriter.Feature.WriteNameAsSymbol};
        byte[] encoded = com.alibaba.fastjson2.JSONB.toBytes(request, write);
        com.alibaba.fastjson2.JSONReader.Feature[] read = {
                com.alibaba.fastjson2.JSONReader.Feature.UseDefaultConstructorAsPossible,
                com.alibaba.fastjson2.JSONReader.Feature.ErrorOnNoneSerializable,
                com.alibaba.fastjson2.JSONReader.Feature.IgnoreAutoTypeNotMatch,
                com.alibaba.fastjson2.JSONReader.Feature.UseNativeObject,
                com.alibaba.fastjson2.JSONReader.Feature.FieldBased};
        RagSearchRequest restored = com.alibaba.fastjson2.JSONB.parseObject(encoded, RagSearchRequest.class, read);
        assertThat(restored).isEqualTo(request);
    }

    @Test
    void dubboWireFeatureCombinationRoundTripsDefaultedSearchRequest() throws Exception {
        // fastjson2 2.0.52..2.0.59 JSONB cannot decode Dubbo's FieldBased record payloads that
        // carry list fields through the security handler: the generated reader hits
        // startArray(TYPED_ANY) and crashes (KI-113 follow-up). This test PINS that known defect
        // so a future fastjson2 upgrade can flip it into a positive round-trip assertion; the
        // memory RAG references pin serialization=hessian2 until then.
        RagSearchRequest request = RagSearchRequest.defaults("acceptance-constraint-20261015",
                "memory.constraint", "房源发布规范要求").normalized();
        com.alibaba.fastjson2.JSONWriter.Feature[] write = {
                com.alibaba.fastjson2.JSONWriter.Feature.WriteClassName,
                com.alibaba.fastjson2.JSONWriter.Feature.FieldBased,
                com.alibaba.fastjson2.JSONWriter.Feature.ErrorOnNoneSerializable,
                com.alibaba.fastjson2.JSONWriter.Feature.ReferenceDetection,
                com.alibaba.fastjson2.JSONWriter.Feature.WriteNulls,
                com.alibaba.fastjson2.JSONWriter.Feature.NotWriteDefaultValue,
                com.alibaba.fastjson2.JSONWriter.Feature.NotWriteHashMapArrayListClassName,
                com.alibaba.fastjson2.JSONWriter.Feature.WriteNameAsSymbol};
        byte[] encoded = com.alibaba.fastjson2.JSONB.toBytes(request, write);
        org.apache.dubbo.rpc.model.FrameworkModel framework = new org.apache.dubbo.rpc.model.FrameworkModel();
        try {
            org.apache.dubbo.common.serialize.fastjson2.Fastjson2SecurityManager manager =
                    new org.apache.dubbo.common.serialize.fastjson2.Fastjson2SecurityManager(framework);
            java.util.Set<String> allowed = new java.util.LinkedHashSet<>();
            for (Class<?> type : List.of(RagSearchRequest.class, RagFilter.class, RagFilterOperator.class,
                    RagValue.class, RagValueType.class, RagRecallOptions.class, RagRecallMode.class,
                    RagFusionOptions.class, RagFusionStrategy.class, RagFailurePolicy.class, java.util.ArrayList.class)) {
                allowed.add(type.getName());
            }
            manager.notifyPrefix(allowed, java.util.Set.of());
            manager.notifyCheckStatus(org.apache.dubbo.common.utils.SerializeCheckStatus.STRICT);
            java.lang.reflect.Method filter = org.apache.dubbo.common.serialize.fastjson2.Fastjson2SecurityManager.class.getDeclaredMethod("getSecurityFilter");
            filter.setAccessible(true);
            Object handler = filter.invoke(manager);
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    com.alibaba.fastjson2.JSONB.parseObject(encoded, RagSearchRequest.class,
                            (com.alibaba.fastjson2.filter.Filter) handler,
                            com.alibaba.fastjson2.JSONReader.Feature.UseDefaultConstructorAsPossible,
                            com.alibaba.fastjson2.JSONReader.Feature.ErrorOnNoneSerializable,
                            com.alibaba.fastjson2.JSONReader.Feature.IgnoreAutoTypeNotMatch,
                            com.alibaba.fastjson2.JSONReader.Feature.UseNativeObject,
                            com.alibaba.fastjson2.JSONReader.Feature.FieldBased))
                    .isInstanceOf(com.alibaba.fastjson2.JSONException.class)
                    .hasMessageContaining("TYPED_ANY");
        } finally { framework.destroy(); }
    }

    @Test
    void javaSerializableWireRoundTripsSearchRequestWithListFields() throws Exception {
        // The hessian2 consumer pin relies on standard Java serialization of the same contract
        // graph; records with generic list fields must round-trip there.
        RagSearchRequest request = new RagSearchRequest(1, "acceptance-constraint-20261015",
                "memory.constraint", "房源发布规范要求",
                List.of(new RagFilter("category", RagFilterOperator.IN,
                        RagValue.strings(List.of("compliance")))),
                null, null, null, null, null, null).normalized();
        var bytes = new java.io.ByteArrayOutputStream();
        try (var output = new java.io.ObjectOutputStream(bytes)) {
            output.writeObject(request);
        }
        try (var input = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            assertThat(input.readObject()).isEqualTo(request);
        }
    }
}
