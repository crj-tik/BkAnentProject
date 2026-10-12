package com.bkanent.rag.config;

import com.bkanent.rag.storage.RagSpaceDefinition;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit deployment-managed spaces; omission does not register a default space. */
@ConfigurationProperties("rag")
public record RagProperties(Map<String, RagSpaceDefinition> spaces) {
    public RagProperties {
        spaces = spaces == null ? Map.of() : Map.copyOf(spaces);
    }
}
