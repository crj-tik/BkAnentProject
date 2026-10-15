package com.bkanent.common.serialize;

import com.bkanent.common.rag.*;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.serialize.ObjectOutput;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * fastjson2ctor SPI round-trips the RAG contract without the WriteClassName/FieldBased
 * features that crash record payloads with list fields through Dubbo's serialization security
 * handler on fastjson2 2.0.52..2.0.59 (KI-113 follow-up).
 */
class Fastjson2ConstructorSerializationTest {
    private final Fastjson2ConstructorSerialization serialization = new Fastjson2ConstructorSerialization();

    @Test void searchRequestWithListFieldsRoundTripsThroughTheSpi() throws Exception {
        RagSearchRequest request = new RagSearchRequest(1, "acceptance-constraint-20261015",
                "memory.constraint", "房源发布规范要求",
                List.of(new RagFilter("category", RagFilterOperator.IN,
                        RagValue.strings(List.of("compliance")))),
                null, null, null, null, null, null).normalized();
        var bytes = new ByteArrayOutputStream();
        ObjectOutput output = serialization.serialize(null, bytes);
        output.writeObject(request);
        output.flushBuffer();
        ObjectInput input = serialization.deserialize(null, new ByteArrayInputStream(bytes.toByteArray()));
        assertThat(input.readObject(RagSearchRequest.class)).isEqualTo(request);
    }

    @Test void statusWithInstantRoundTripsThroughTheSpi() throws Exception {
        RagIndexStatus status = new RagIndexStatus(1, "req", "evt", "memory.constraint", "constraint:YQ",
                3, "a".repeat(64), RagIndexState.RETRY_PENDING, 3, false,
                new RagBackendStatus(0, 2, RagErrorCode.DEPENDENCY_UNAVAILABLE,
                        java.time.Instant.parse("2026-10-15T00:00:00Z")),
                new RagBackendStatus(3, 0, null, null), RagErrorCode.DEPENDENCY_UNAVAILABLE);
        var bytes = new ByteArrayOutputStream();
        ObjectOutput output = serialization.serialize(null, bytes);
        output.writeObject(status);
        output.flushBuffer();
        ObjectInput input = serialization.deserialize(null, new ByteArrayInputStream(bytes.toByteArray()));
        assertThat(input.readObject(RagIndexStatus.class)).isEqualTo(status);
    }

    @Test void primitivesAndStringsRoundTripThroughTheSpi() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ObjectOutput output = serialization.serialize(null, bytes);
        output.writeInt(7);
        output.writeUTF("value");
        output.flushBuffer();
        ObjectInput input = serialization.deserialize(null, new ByteArrayInputStream(bytes.toByteArray()));
        assertThat(input.readInt()).isEqualTo(7);
        assertThat(input.readUTF()).isEqualTo("value");
    }

    @Test void untypedReadYieldsPlainContainersNeverForeignTypes() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ObjectOutput output = serialization.serialize(null, bytes);
        output.writeObject(new java.util.HashSet<>(List.of("x")));
        output.flushBuffer();
        ObjectInput input = serialization.deserialize(null, new ByteArrayInputStream(bytes.toByteArray()));
        Object value = input.readObject();
        // No class-name tags on this wire: an untyped read can only produce plain containers,
        // never an attacker-chosen class; typed reads go through the declared method signature.
        assertThat(value).isInstanceOf(java.util.Collection.class);
    }

    @Test void ragExceptionRoundTripsThroughTheThrowableEnvelope() throws Exception {
        // Dubbo's readThrowable() uses the untyped read; the envelope carries only the safe
        // failure contract (code + sanitized field), never a foreign exception class.
        var bytes = new ByteArrayOutputStream();
        ObjectOutput output = serialization.serialize(null, bytes);
        output.writeThrowable(new com.bkanent.common.rag.RagException(
                com.bkanent.common.rag.RagErrorCode.SPACE_NOT_READY, "spaceId"));
        output.flushBuffer();
        ObjectInput input = serialization.deserialize(null, new ByteArrayInputStream(bytes.toByteArray()));
        Object restored = input.readObject();
        assertThat(restored).isInstanceOf(com.bkanent.common.rag.RagException.class);
        com.bkanent.common.rag.RagException failure = (com.bkanent.common.rag.RagException) restored;
        assertThat(failure.code()).isEqualTo(com.bkanent.common.rag.RagErrorCode.SPACE_NOT_READY);
        assertThat(failure.field()).isEqualTo("spaceId");
    }

    @Test void recordWithEmptyListFieldRoundTripsAsEmptyList() throws Exception {
        // Empty failedStages must stay empty: a null-filled phantom element would corrupt
        // response semantics on the HTTP surface (observed once on the live wire).
        RagSearchResponse response = new RagSearchResponse(1, "r", "memory.constraint", "rrf-v1",
                RagSearchMode.HYBRID_RERANK, RagSearchMode.HYBRID_RERANK, false, List.of(), List.of(), List.of());
        var bytes = new ByteArrayOutputStream();
        ObjectOutput output = serialization.serialize(null, bytes);
        output.writeObject(response);
        output.flushBuffer();
        ObjectInput input = serialization.deserialize(null, new ByteArrayInputStream(bytes.toByteArray()));
        RagSearchResponse restored = input.readObject(RagSearchResponse.class);
        assertThat(restored.failedStages()).isEmpty();
        assertThat(restored).isEqualTo(response);
    }
}
