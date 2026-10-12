package com.bkanent.rag.storage;

import com.bkanent.common.rag.*;
import com.bkanent.rag.index.IndexDocument;
import com.bkanent.rag.retrieval.RecallHit;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Same typed, complete projection in both stores; reserved identity cannot be overridden. */
public final class ProjectionCodec {
    private static final Set<String> RESERVED = Set.of("documentId", "source_type", "source_id",
            "projectionVersion", "schemaVersion", "contentHash", "tombstone");
    private ProjectionCodec() { }

    public static Map<String, Object> metadata(IndexDocument document, RagSpaceDefinition space) {
        if (!Objects.equals(document.schemaVersion(), space.schemaVersion())) invalid();
        RagDocument projection = document.document();
        ProjectionFilters.validateDocument(space, projection);
        if (projection == null || projection.content() == null
                || projection.content().getBytes(StandardCharsets.UTF_8).length > space.limits().maxDocumentUtf8Bytes()) invalid();
        Map<String, Object> attributes = new HashMap<>();
        for (var entry : projection.attributes().entrySet()) {
            if (RESERVED.contains(entry.getKey()) || !space.fields().containsKey(entry.getKey())
                    || space.fields().get(entry.getKey()).type() != entry.getValue().type()) invalid();
            attributes.put(entry.getKey(), ProjectionFilters.raw(entry.getValue()));
        }
        space.fields().forEach((name, field) -> {
            if (field.required() && !attributes.containsKey(name)) invalid();
        });
        attributes.put("documentId", document.documentId());
        attributes.put(space.vectorSchema().sourceTypeField(), projection.sourceType());
        attributes.put(space.vectorSchema().sourceIdField(), projection.sourceId());
        attributes.put("projectionVersion", document.revision());
        attributes.put("schemaVersion", document.schemaVersion());
        attributes.put("contentHash", document.contentHash());
        attributes.put("tombstone", false);
        return attributes;
    }

    public static RecallHit hit(String physicalId, String content, Map<String, Object> metadata,
                                Double score, RagRecallMode mode, RagSpaceDefinition space) {
        if (metadata == null || !metadata.containsKey("documentId")
                || !metadata.containsKey("projectionVersion") || !metadata.containsKey("contentHash")) {
            throw new RagException(RagErrorCode.SPACE_NOT_READY);
        }
        Map<String, RagValue> fields = new HashMap<>();
        for (var entry : space.fields().entrySet()) {
            Object value = metadata.get(entry.getKey());
            if (value != null) fields.put(entry.getKey(), typed(value, entry.getValue().type()));
        }
        String hash = String.valueOf(metadata.get("contentHash"));
        fields.put("contentHash", RagValue.string(hash));
        RagCandidate candidate = new RagCandidate(String.valueOf(metadata.get("documentId")),
                String.valueOf(metadata.get(space.vectorSchema().sourceTypeField())),
                String.valueOf(metadata.get(space.vectorSchema().sourceIdField())), content,
                ((Number) metadata.get("projectionVersion")).longValue(), String.valueOf(metadata.get("schemaVersion")),
                List.of(mode), mode == RagRecallMode.KEYWORD ? score : null,
                mode == RagRecallMode.VECTOR ? score : null, null, null, null, null, null, fields);
        return new RecallHit(candidate, hash);
    }

    public static RagCandidate publicCandidate(RagCandidate c, RagSpaceDefinition space) {
        Map<String, RagValue> metadata = new HashMap<>();
        c.metadata().forEach((key, value) -> { if (space.returnFields().contains(key)) metadata.put(key, value); });
        return new RagCandidate(c.documentId(), c.sourceType(), c.sourceId(), c.content(), c.projectionVersion(),
                c.schemaVersion(), c.sources(), c.keywordScore(), c.vectorScore(), c.fusionScore(),
                c.rawRerankScore(), c.relevanceScore(), c.scoreKind(), c.scoringVersion(), metadata);
    }

    private static RagValue typed(Object value, RagValueType type) {
        return switch (type) {
            case STRING -> RagValue.string(String.valueOf(value));
            case NUMBER -> RagValue.number((Number) value);
            case BOOLEAN -> RagValue.bool((Boolean) value);
            case INSTANT -> RagValue.instant(Instant.parse(String.valueOf(value)));
            case STRING_LIST -> RagValue.strings(((List<?>) value).stream().map(String::valueOf).toList());
            case NUMBER_LIST -> RagValue.numbers(((List<?>) value).stream().map(v -> (Number) v).toList());
        };
    }

    public static String physicalId(String logicalId) {
        return UUID.nameUUIDFromBytes(("rag-physical-id-v1\u0000" + logicalId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void invalid() { throw new RagException(RagErrorCode.INVALID_ARGUMENT, "document"); }
}
