package com.bkanent.rag.index;

import com.bkanent.common.rag.RagCanonicalHash;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagIndexEvent;
import com.bkanent.common.rag.RagIndexOperation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;

/** Canonical replay JSON holds business DTOs only, never RPC authentication parameters. */
final class IndexPayloadCodec {
    private static final int MAX_PAYLOAD_BYTES = 1_048_576;
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule())
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    String encode(RagIndexEvent event) {
        validate(event);
        try {
            String payload = mapper.writeValueAsString(event);
            if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
                throw new RagException(RagErrorCode.INVALID_ARGUMENT, "document");
            }
            return payload;
        } catch (JsonProcessingException exception) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "document");
        }
    }

    RagIndexEvent decode(String payload) {
        if (payload == null) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "ledger"); }
        try {
            RagIndexEvent event = mapper.readValue(payload, RagIndexEvent.class);
            validate(event);
            return event;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "ledger");
        }
    }

    private void validate(RagIndexEvent e) {
        if (e == null || e.contractVersion() != 1 || e.revision() < 1 || e.operation() == null
                || e.createdAt() == null || e.contentHash() == null
                || !e.contentHash().matches("[0-9a-f]{64}")) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "event");
        }
        identifier(e.eventId(), 128, "eventId");
        identifier(e.spaceId(), 128, "spaceId");
        identifier(e.documentId(), 512, "documentId");
        identifier(e.schemaVersion(), 128, "schemaVersion");
        if ((e.operation() == RagIndexOperation.DELETE) != (e.document() == null)) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "document");
        }
        if (!RagCanonicalHash.documentHash(e.operation(), e.document()).equals(e.contentHash())) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "contentHash");
        }
    }

    static void identifier(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, field);
        }
    }
}
