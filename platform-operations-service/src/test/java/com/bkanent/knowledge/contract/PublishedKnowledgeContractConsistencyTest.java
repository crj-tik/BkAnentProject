package com.bkanent.knowledge.contract;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PublishedKnowledgeContractConsistencyTest {
    @Test
    void publishedRequestSchemasAndOperationReferencesStayAligned() throws IOException {
        Path contracts = findContractsDirectory();
        Map<?, ?> platform = readYaml(contracts.resolve("platform-operations-v1.openapi.yaml"));
        Map<?, ?> published = readYaml(contracts.resolve("platform-operations-published-knowledge-v1.openapi.yaml"));
        Map<?, ?> platformSchemas = map(map(platform, "components"), "schemas");
        Map<?, ?> publishedSchemas = map(map(published, "components"), "schemas");

        for (String name : List.of("KnowledgeRef", "PublishedSearchRequest", "BatchReadRequest", "CheckRequest")) {
            assertNotNull(publishedSchemas.get(name), name);
            assertEquals(publishedSchemas.get(name), platformSchemas.get(name), name);
        }

        Map<?, ?> paths = map(platform, "paths");
        for (var operation : Map.of("search", "PublishedSearchRequest", "batch-read", "BatchReadRequest",
                "check", "CheckRequest").entrySet()) {
            Map<?, ?> path = map(paths, "/platform-operations/knowledge/v1/published/" + operation.getKey());
            Map<?, ?> content = map(map(map(path, "post"), "requestBody"), "content");
            Map<?, ?> schema = map(map(content, "application/json"), "schema");
            assertEquals("#/components/schemas/" + operation.getValue(), schema.get("$ref"));
        }
    }

    private static Path findContractsDirectory() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path contracts = directory.resolve("docs").resolve("contracts");
            if (Files.isRegularFile(contracts.resolve("platform-operations-v1.openapi.yaml"))) return contracts;
            directory = directory.getParent();
        }
        throw new IllegalStateException("Cannot locate repository contracts directory");
    }

    private static Map<?, ?> readYaml(Path path) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return (Map<?, ?>) new Yaml(new SafeConstructor(options)).load(reader);
        }
    }

    private static Map<?, ?> map(Map<?, ?> parent, String key) {
        Object value = parent.get(key);
        assertNotNull(value, key);
        return (Map<?, ?>) value;
    }
}
