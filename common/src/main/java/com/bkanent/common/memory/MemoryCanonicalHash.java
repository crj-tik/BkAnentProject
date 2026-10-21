package com.bkanent.common.memory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Canonical, typed, length-framed hashing for v2 payloads, independent of RPC serializers. */
public final class MemoryCanonicalHash {
    private static final String DOMAIN = "memory.request-body.v2";

    private MemoryCanonicalHash() { }

    /** Stable content identity. The source hash is excluded to avoid a self-referential digest. */
    public static String messageHash(MemoryMessage message) {
        MemoryContract.required(message, MemoryException.Field.HASH);
        var fields = new java.util.ArrayList<>(java.util.Arrays.asList(message.messageId(), message.contextKey(), message.sequence(), message.association(),
                message.role(), message.text(), message.toolCalls(), message.toolResponses(),
                message.source().sourceEventId(), message.source().version().revision(), message.source().version().schemaVersion(), message.dependencies()));
        if (message.matterIntent() != null) fields.add(message.matterIntent());
        return contentHash(fields);
    }

    public static String matterHash(MemoryMatter matter) {
        return contentHash(java.util.Arrays.asList(matter.matterId(), matter.contextKey(), matter.version().revision(),
                matter.version().schemaVersion(), matter.goal(), matter.state(), matter.closure(), matter.dependencies()));
    }

    /** Includes all summary fields except its own content hash. */
    public static String summaryHash(MemorySummary summary) {
        var fields = new java.util.ArrayList<>(java.util.Arrays.asList(summary.summaryId(), summary.contextKey(), summary.association(),
                summary.version().revision(), summary.version().schemaVersion(), summary.expectedConversationRevision(),
                summary.runVersion(), summary.coveredRange(), summary.toolGroupRanges(), summary.items(), summary.entities(), summary.dependencies()));
        // Existing summaries without result anchors retain their established digest.
        if (!summary.toolResults().isEmpty()) fields.add(summary.toolResults());
        return contentHash(fields);
    }

    public static String entityHash(MemoryEntityAnchor entity) {
        return contentHash(java.util.Arrays.asList(entity.entityId(), entity.contextKey(), entity.matterId(),
                entity.version().revision(), entity.version().schemaVersion(), entity.aliases(), entity.briefDefinition(), entity.dependencies()));
    }

    /** Hashes a full immutable experience revision while excluding only its self-referential content hash. */
    public static String experienceHash(MemoryExperience experience) {
        return contentHash(java.util.Arrays.asList(experience.experienceId(), experience.contextKey(), experience.association(),
                experience.version().revision(), experience.version().schemaVersion(), experience.scenario(), experience.applicability(),
                experience.conclusion(), experience.state(), experience.changeReason(), experience.supersedesRevision(),
                experience.confidence(), experience.activation(), experience.lastObservedAt(), experience.lastValidatedAt(),
                experience.policyVersion(), experience.evidence()));
    }

    public static String contentHash(Object value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writeString(output, "memory.content.v2");
            write(output, value, false);
            output.flush();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (Exception exception) {
            throw new MemoryException(MemoryErrorCode.INVALID_ARGUMENT, MemoryException.Field.HASH);
        }
    }

    /** Hashes only the payload record fields; operation/request identity stay in its signed envelope. */
    public static String requestBodyHash(MemoryV2Request request) {
        MemoryContract.required(request, MemoryException.Field.HASH);
        MemoryContract.required(request.envelope(), MemoryException.Field.IDENTITY);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
            DataOutputStream output = new DataOutputStream(bytes);
            writeString(output, DOMAIN);
            write(output, request, true);
            output.flush();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (MemoryException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MemoryException(MemoryErrorCode.INVALID_ARGUMENT, MemoryException.Field.HASH);
        }
    }

    private static void write(DataOutputStream output, Object value, boolean rootRequest) throws Exception {
        if (value == null) { output.writeByte(0); return; }
        if (value instanceof String text) { output.writeByte(1); writeString(output, text); return; }
        if (value instanceof Boolean bool) { output.writeByte(2); output.writeBoolean(bool); return; }
        if (value instanceof Integer number) { output.writeByte(3); output.writeInt(number); return; }
        if (value instanceof Long number) { output.writeByte(4); output.writeLong(number); return; }
        if (value instanceof Short number) { output.writeByte(5); output.writeShort(number); return; }
        if (value instanceof Byte number) { output.writeByte(6); output.writeByte(number); return; }
        if (value instanceof BigDecimal number) { output.writeByte(7); writeString(output, number.toPlainString()); return; }
        if (value instanceof Double number && Double.isFinite(number)) { output.writeByte(14); output.writeLong(Double.doubleToLongBits(number)); return; }
        if (value instanceof Instant instant) {
            output.writeByte(8); output.writeLong(instant.getEpochSecond()); output.writeInt(instant.getNano()); return;
        }
        if (value instanceof Enum<?> item) {
            output.writeByte(9); writeString(output, item.getDeclaringClass().getName()); writeString(output, item.name()); return;
        }
        if (value instanceof byte[] array) { output.writeByte(10); output.writeInt(array.length); output.write(array); return; }
        if (value instanceof Map<?, ?> map) {
            output.writeByte(11);
            List<Map.Entry<String, Object>> entries = new ArrayList<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new MemoryException(MemoryErrorCode.INVALID_ARGUMENT, MemoryException.Field.HASH);
                @SuppressWarnings("unchecked") Object nested = entry.getValue();
                entries.add(new java.util.AbstractMap.SimpleImmutableEntry<>(key, nested));
            }
            entries.sort(Comparator.comparing(Map.Entry::getKey));
            output.writeInt(entries.size());
            for (var entry : entries) { writeString(output, entry.getKey()); write(output, entry.getValue(), false); }
            return;
        }
        if (value instanceof Collection<?> items) {
            if (value instanceof java.util.Set<?>) throw new MemoryException(MemoryErrorCode.INVALID_ARGUMENT, MemoryException.Field.HASH);
            output.writeByte(12); output.writeInt(items.size());
            for (Object item : items) write(output, item, false);
            return;
        }
        Class<?> type = value.getClass();
        if (type.isRecord() && type.getPackageName().equals(MemoryV2Request.class.getPackageName())) {
            output.writeByte(13); writeString(output, type.getName());
            RecordComponent[] components = type.getRecordComponents();
            int included = components.length;
            if (rootRequest && value instanceof MemoryV2Request) included--;
            output.writeInt(included);
            for (RecordComponent component : components) {
                if (rootRequest && value instanceof MemoryV2Request && component.getName().equals("envelope")) continue;
                writeString(output, component.getName());
                write(output, component.getAccessor().invoke(value), false);
            }
            return;
        }
        throw new MemoryException(MemoryErrorCode.INVALID_ARGUMENT, MemoryException.Field.HASH);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
