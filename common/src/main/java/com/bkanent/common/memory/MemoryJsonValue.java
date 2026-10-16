package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Closed JSON tree without polymorphic interfaces, framework nodes or arbitrary Object.
 * NUMBER holds a JSON decimal lexeme (not a Java Number subtype); JSON null is explicit.
 * Bounds cover the entire tree, including nested objects, arrays, keys and scalar text.
 * This is serialization/shape validation, not a whitelist of authorized tool fields.
 */
public record MemoryJsonValue(Type type, String scalar, List<MemoryJsonValue> elements,
                              Map<String, MemoryJsonValue> properties) implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 4096;
    private static final int MAX_CHARACTERS = 131072;
    public enum Type { NULL, STRING, NUMBER, BOOLEAN, ARRAY, OBJECT }

    public MemoryJsonValue {
        MemoryContract.required(type, MemoryException.Field.JSON);
        MemoryContract.require(switch (type) {
            case NULL -> scalar == null && elements == null && properties == null;
            case STRING, NUMBER, BOOLEAN -> scalar != null && elements == null && properties == null;
            case ARRAY -> scalar == null && elements != null && properties == null;
            case OBJECT -> scalar == null && elements == null && properties != null;
        }, MemoryException.Field.JSON);
        if (scalar != null) MemoryContract.require(scalar.length() <= MemoryContract.MAX_TEXT, MemoryException.Field.JSON);
        if (type == Type.NUMBER) MemoryContract.require(scalar.length() <= 128
                && scalar.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"), MemoryException.Field.JSON);
        if (type == Type.BOOLEAN) MemoryContract.require("true".equals(scalar) || "false".equals(scalar), MemoryException.Field.JSON);
        if (type == Type.STRING) MemoryContract.require(validUnicode(scalar), MemoryException.Field.JSON);
        if (elements != null) elements = MemoryContract.list(elements, MemoryException.Field.JSON);
        if (properties != null) {
            MemoryContract.require(properties.size() <= MemoryContract.MAX_LIST, MemoryException.Field.JSON);
            Map<String, MemoryJsonValue> copy = new LinkedHashMap<>();
            properties.forEach((key, value) -> {
                MemoryContract.require(key != null && key.length() <= 256
                        && validUnicode(key) && key.codePoints().noneMatch(Character::isISOControl)
                        && value != null, MemoryException.Field.JSON);
                copy.put(key, value);
            });
            properties = Collections.unmodifiableMap(copy);
        }
        int[] budget = {1, scalar == null ? 0 : scalar.length()};
        if (elements != null) for (MemoryJsonValue child : elements) inspect(child, 2, budget);
        if (properties != null) for (Map.Entry<String, MemoryJsonValue> entry : properties.entrySet()) {
            budget[1] += entry.getKey().length();
            inspect(entry.getValue(), 2, budget);
        }
        MemoryContract.require(budget[1] <= MAX_CHARACTERS, MemoryException.Field.JSON);
    }

    private static boolean validUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(current)) return false;
        }
        return true;
    }

    private static void inspect(MemoryJsonValue value, int depth, int[] budget) {
        MemoryContract.require(depth <= MAX_DEPTH && ++budget[0] <= MAX_NODES, MemoryException.Field.JSON);
        if (value.scalar != null) budget[1] += value.scalar.length();
        MemoryContract.require(budget[1] <= MAX_CHARACTERS, MemoryException.Field.JSON);
        if (value.elements != null) for (MemoryJsonValue child : value.elements) inspect(child, depth + 1, budget);
        if (value.properties != null) for (Map.Entry<String, MemoryJsonValue> entry : value.properties.entrySet()) {
            budget[1] += entry.getKey().length();
            inspect(entry.getValue(), depth + 1, budget);
        }
    }

    public static MemoryJsonValue nil() { return new MemoryJsonValue(Type.NULL, null, null, null); }
    public static MemoryJsonValue string(String value) { return new MemoryJsonValue(Type.STRING, value, null, null); }
    public static MemoryJsonValue number(String value) { return new MemoryJsonValue(Type.NUMBER, value, null, null); }
    public static MemoryJsonValue bool(boolean value) { return new MemoryJsonValue(Type.BOOLEAN, Boolean.toString(value), null, null); }
    public static MemoryJsonValue array(List<MemoryJsonValue> values) { return new MemoryJsonValue(Type.ARRAY, null, values, null); }
    public static MemoryJsonValue object(Map<String, MemoryJsonValue> values) { return new MemoryJsonValue(Type.OBJECT, null, null, values); }
}
