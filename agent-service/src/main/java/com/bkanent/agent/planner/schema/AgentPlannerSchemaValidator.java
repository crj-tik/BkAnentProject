package com.bkanent.agent.planner.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Planner 输入输出 Schema 校验器。
 *
 * <p>对动作执行前后的结构化数据做基础类型、必填字段、数组项和枚举值校验。</p>
 */
@Component
public class AgentPlannerSchemaValidator {

    private final ObjectMapper objectMapper;

    public AgentPlannerSchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validateInput(String action, String inputSchema, Map<String, Object> arguments) {
        validateObjectSchema(action + " 入参", inputSchema, arguments);
    }

    public void validateOutput(String action, String outputSchema, Map<String, Object> payload) {
        validateObjectSchema(action + " 出参", outputSchema, payload);
    }

    private void validateObjectSchema(String scene, String schemaText, Map<String, Object> payload) {
        if (schemaText == null || schemaText.isBlank()) {
            return;
        }
        try {
            JsonNode schema = objectMapper.readTree(schemaText);
            validateBySchema(scene, schema, payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(scene + " schema 非法: " + schemaText, exception);
        }
    }

    private void validateBySchema(String fieldPath, JsonNode schema, Object value) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return;
        }
        String expectedType = schema.path("type").asText();
        if (!expectedType.isBlank() && !matchesType(expectedType, value)) {
            throw new IllegalArgumentException(fieldPath + " 类型不匹配，期望 " + expectedType);
        }
        validateEnum(fieldPath, schema, value);
        if ("object".equals(expectedType)) {
            validateObject(fieldPath, schema, value);
            return;
        }
        if ("array".equals(expectedType)) {
            validateArray(fieldPath, schema, value);
        }
    }

    private void validateObject(String fieldPath, JsonNode schema, Object value) {
        if (value == null) {
            return;
        }
        if (!(value instanceof Map<?, ?> mapValue)) {
            throw new IllegalArgumentException(fieldPath + " 类型不匹配，期望 object");
        }
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode node : required) {
                String field = node.asText();
                Object requiredValue = mapValue.get(field);
                if (requiredValue == null || isBlankString(requiredValue)) {
                    throw new IllegalArgumentException(fieldPath + "." + field + " 缺少必填字段");
                }
            }
        }
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!mapValue.containsKey(field.getKey())) {
                continue;
            }
            validateBySchema(fieldPath + "." + field.getKey(), field.getValue(), mapValue.get(field.getKey()));
        }
    }

    private void validateArray(String fieldPath, JsonNode schema, Object value) {
        if (value == null) {
            return;
        }
        List<?> values = toList(value);
        JsonNode itemsSchema = schema.path("items");
        for (int index = 0; index < values.size(); index++) {
            validateBySchema(fieldPath + "[" + index + "]", itemsSchema, values.get(index));
        }
    }

    private void validateEnum(String fieldPath, JsonNode schema, Object value) {
        JsonNode enumNode = schema.path("enum");
        if (!enumNode.isArray() || value == null) {
            return;
        }
        for (JsonNode candidate : enumNode) {
            Object candidateValue = objectMapper.convertValue(candidate, Object.class);
            if (candidateValue != null && candidateValue.equals(value)) {
                return;
            }
            if (candidateValue != null && String.valueOf(candidateValue).equals(String.valueOf(value))) {
                return;
            }
        }
        throw new IllegalArgumentException(fieldPath + " 枚举值不合法，允许值为 " + enumNode);
    }

    private List<?> toList(Object value) {
        if (value instanceof List<?> listValue) {
            return listValue;
        }
        if (value instanceof Collection<?> collectionValue) {
            return List.copyOf(collectionValue);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> result = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                result.add(Array.get(value, index));
            }
            return result;
        }
        throw new IllegalArgumentException("数组类型不匹配，期望 array");
    }

    private boolean isBlankString(Object value) {
        return value instanceof String text && text.isBlank();
    }

    private boolean matchesType(String expectedType, Object value) {
        if (expectedType == null || expectedType.isBlank() || value == null) {
            return true;
        }
        return switch (expectedType) {
            case "array" -> value instanceof Collection<?> || value.getClass().isArray();
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map;
            default -> true;
        };
    }
}
