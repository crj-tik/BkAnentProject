package com.bkanent.common.a2a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.spec.DataPart;
import io.a2a.spec.MessageSendConfiguration;
import io.a2a.spec.Part;
import io.a2a.spec.TextPart;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts official A2A Message parts and metadata into ReactAgent input.
 */
public final class A2aInputParser {

    private static final String STRUCTURED_INPUT_LABEL = "[Structured A2A input]";

    private final ObjectMapper objectMapper;

    public A2aInputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public A2aInput parse(RequestContext context, A2aOutputPolicy policy) {
        if (context == null || context.getMessage() == null) {
            throw new A2aInputException("INVALID_INPUT", "A2A message is missing");
        }
        List<String> textParts = new ArrayList<>();
        Map<String, Object> structuredContext = new LinkedHashMap<>();
        for (Part<?> part : context.getMessage().getParts()) {
            if (part instanceof TextPart textPart && StringUtils.hasText(textPart.getText())) {
                textParts.add(textPart.getText().trim());
            }
            else if (part instanceof DataPart dataPart && dataPart.getData() != null) {
                dataPart.getData().forEach((key, value) -> {
                    if (key != null && value != null) {
                        structuredContext.put(key, value);
                    }
                });
            }
        }

        String structuredJson = serializeStructuredContext(structuredContext);
        String instruction = String.join(System.lineSeparator(), textParts).trim();
        if (StringUtils.hasText(structuredJson)) {
            instruction = instruction.isBlank()
                    ? STRUCTURED_INPUT_LABEL + System.lineSeparator() + structuredJson
                    : instruction + System.lineSeparator() + System.lineSeparator()
                    + STRUCTURED_INPUT_LABEL + System.lineSeparator() + structuredJson;
        }
        if (instruction.length() > policy.maxInputChars()) {
            throw new A2aInputException("INPUT_TOO_LARGE", "A2A input exceeds the configured size limit");
        }

        Map<String, Object> metadata = context.getParams() == null || context.getParams().metadata() == null
                ? Map.of()
                : immutableCopy(context.getParams().metadata());
        String metadataJson = serializeMetadata(metadata);
        if (metadataJson.length() > policy.maxInputChars()) {
            throw new A2aInputException("METADATA_TOO_LARGE", "A2A metadata exceeds the configured size limit");
        }

        MessageSendConfiguration configuration = context.getConfiguration();
        return new A2aInput(instruction, Map.copyOf(structuredContext), metadata,
                policy.requestsJson(configuration));
    }

    private String serializeStructuredContext(Map<String, Object> structuredContext) {
        if (structuredContext.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(structuredContext);
        }
        catch (JsonProcessingException exception) {
            throw new A2aInputException("INVALID_STRUCTURED_INPUT", "A2A structured input is not serializable");
        }
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        }
        catch (JsonProcessingException exception) {
            throw new A2aInputException("INVALID_METADATA", "A2A metadata is not serializable");
        }
    }

    private Map<String, Object> immutableCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return Map.copyOf(copy);
    }
}
