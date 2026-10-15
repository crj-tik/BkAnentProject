package com.bkanent.common.rag;

import com.alibaba.fastjson2.JSONB;
import org.junit.jupiter.api.Test;

import java.time.Instant;

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
}
