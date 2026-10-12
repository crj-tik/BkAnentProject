package com.bkanent.rag.storage;

import com.bkanent.common.rag.*;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import java.math.BigDecimal;
import java.util.*;

/** Shared typed filter semantics. CONTAINS_CI is applied to bounded, authorized batches. */
public final class ProjectionFilters {
    private ProjectionFilters() { }

    public static void validateDocument(RagSpaceDefinition space, RagDocument document) {
        if (document == null || document.content() == null || document.content().isBlank()
                || document.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > space.limits().maxDocumentUtf8Bytes()) invalid("document");
        for (var entry : document.attributes().entrySet()) {
            RagSpaceDefinition.FilterField field = space.fields().get(entry.getKey());
            if (field == null || field.type() != entry.getValue().type()
                    || !entry.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}")) invalid(entry.getKey());
            raw(entry.getValue());
        }
        space.fields().forEach((name, field) -> { if (field.required() && !document.attributes().containsKey(name)) invalid(name); });
    }

    public static void validate(RagSpaceDefinition space, List<RagFilter> filters) {
        for (RagFilter filter : filters) {
            RagSpaceDefinition.FilterField field = space.fields().get(filter.field());
            if (field == null || !filter.field().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                    || !field.operators().contains(filter.op())) invalid(filter.field());
            RagValueType actual = filter.value().type();
            RagValueType expected = field.type();
            if (filter.op() == RagFilterOperator.IN) {
                expected = expected == RagValueType.STRING ? RagValueType.STRING_LIST
                        : expected == RagValueType.NUMBER ? RagValueType.NUMBER_LIST : expected;
            }
            if (actual != expected) invalid(filter.field());
            if (filter.op() == RagFilterOperator.IN && actual != RagValueType.STRING_LIST && actual != RagValueType.NUMBER_LIST) invalid(filter.field());
            if ((filter.op() == RagFilterOperator.GTE || filter.op() == RagFilterOperator.LTE)
                    && actual != RagValueType.NUMBER && actual != RagValueType.INSTANT) invalid(filter.field());
            if (filter.op() == RagFilterOperator.CONTAINS_CI && actual != RagValueType.STRING) invalid(filter.field());
            raw(filter.value());
        }
    }

    public static Object raw(RagValue value) {
        return switch (value.type()) {
            case STRING -> value.scalar();
            case INSTANT -> canonicalInstant(value.scalar());
            case NUMBER -> finiteNumber(value.scalar());
            case BOOLEAN -> Boolean.valueOf(value.scalar());
            case STRING_LIST -> value.values();
            case NUMBER_LIST -> value.values().stream().map(v -> (Object) finiteNumber(v)).toList();
        };
    }

    private static double finiteNumber(String text) {
        BigDecimal decimal = new BigDecimal(text);
        double value = decimal.doubleValue();
        if (!Double.isFinite(value) || decimal.signum() != 0 && value == 0) invalid("numberRange");
        // Both backends use double. Reject values not round-trippable rather than changing a hard filter boundary.
        if (BigDecimal.valueOf(value).compareTo(decimal) != 0) invalid("numberPrecision");
        return value;
    }

    private static String canonicalInstant(String text) {
        java.time.Instant instant = java.time.Instant.parse(text);
        java.math.BigInteger epochNanos = java.math.BigInteger.valueOf(instant.getEpochSecond())
                .multiply(java.math.BigInteger.valueOf(1000000000L)).add(java.math.BigInteger.valueOf(instant.getNano()));
        // ES date_nanos supports unsigned dates within the positive signed-long nanosecond interval.
        if (epochNanos.signum() < 0 || epochNanos.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0) invalid("instantRange");
        return new java.time.format.DateTimeFormatterBuilder().appendInstant(9).toFormatter().format(instant);
    }

    public static Filter.Expression vector(List<RagFilter> filters) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op combined = null;
        for (RagFilter f : filters) {
            Object value = vectorLiteral(raw(f.value()));
            FilterExpressionBuilder.Op next = switch (f.op()) {
                case EQ -> builder.eq(f.field(), value);
                case IN -> builder.in(f.field(), ((List<?>) value).toArray());
                case GTE -> builder.gte(f.field(), value);
                case LTE -> builder.lte(f.field(), value);
                case CONTAINS_CI -> null;
            };
            if (next != null) combined = combined == null ? next : builder.and(combined, next);
        }
        return combined == null ? null : combined.build();
    }

    private static Object vectorLiteral(Object value) {
        if (value instanceof List<?> values) return values.stream().map(ProjectionFilters::vectorLiteral).toList();
        if (!(value instanceof String text)) return value;
        // Spring AI 1.1.2 wraps literals in quotes without escaping. Escape *only* literal content.
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20 || ch == 0x7f) invalid("filterValue");
                    escaped.append(ch);
                }
            }
        }
        return escaped.toString();
    }

    public static boolean matches(Map<String, RagValue> attributes, List<RagFilter> filters) {
        for (RagFilter f : filters) {
            RagValue actual = attributes.get(f.field());
            if (actual == null) return false;
            String text = actual.scalar();
            boolean match = switch (f.op()) {
                case EQ -> compare(actual, f.value().scalar()) == 0;
                case IN -> f.value().values().stream().anyMatch(v -> compare(actual, v) == 0);
                case GTE -> compare(actual, f.value().scalar()) >= 0;
                case LTE -> compare(actual, f.value().scalar()) <= 0;
                case CONTAINS_CI -> text != null && text.toLowerCase(Locale.ROOT)
                        .contains(f.value().scalar().toLowerCase(Locale.ROOT));
            };
            if (!match) return false;
        }
        return true;
    }

    private static int compare(RagValue actual, String expected) {
        if (actual.scalar() == null) return -1;
        if (actual.type() == RagValueType.NUMBER) return new BigDecimal(actual.scalar()).compareTo(new BigDecimal(expected));
        if (actual.type() == RagValueType.INSTANT) return java.time.Instant.parse(actual.scalar()).compareTo(java.time.Instant.parse(expected));
        return actual.scalar().compareTo(expected);
    }

    private static void invalid(String field) { throw new RagException(RagErrorCode.INVALID_ARGUMENT, field); }
}
