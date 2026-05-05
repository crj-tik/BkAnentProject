package com.bkanent.agent.mcp;

import com.bkanent.agent.config.MilvusProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MilvusMcpToolImpl implements MilvusMcpTool {

    private final MilvusProperties milvusProperties;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final RestClient restClient;

    public MilvusMcpToolImpl(MilvusProperties milvusProperties, ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.milvusProperties = milvusProperties;
        this.embeddingModelProvider = embeddingModelProvider;
        this.restClient = RestClient.builder()
                .baseUrl(milvusProperties.getEndpoint())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + defaultToken(milvusProperties.getToken()))
                .build();
    }

    @Override
    public void initializeCollection(MilvusCollectionInitRequest request) {
        int dimension = request.dimension() != null ? request.dimension() : detectEmbeddingDimension();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("collectionName", resolveCollectionName(request.collectionName()));
        payload.put("dbName", defaultDatabase());
        payload.put("schema", buildSchema(dimension));
        payload.put("indexParams", buildIndexParams());

        JsonNode response = post("/v2/vectordb/collections/create", payload);
        if (!isSuccess(response) && !messageContains(response, "already exists")) {
            throw new IllegalStateException("milvus create collection failed: " + extractMessage(response));
        }
    }

    @Override
    public void upsert(MilvusUpsertRequest request) {
        if (request.documents() == null || request.documents().isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("collectionName", resolveCollectionName(request.collectionName()));
        payload.put("dbName", defaultDatabase());
        payload.put("data", request.documents().stream().map(this::toMilvusEntity).toList());

        JsonNode response = post("/v2/vectordb/entities/upsert", payload);
        if (!isSuccess(response)) {
            throw new IllegalStateException("milvus upsert failed: " + extractMessage(response));
        }
    }

    @Override
    public List<MilvusSearchResult> search(String collectionName, String query, int topK) {
        if (!milvusProperties.isEnabled()) {
            return List.of(new MilvusSearchResult(resolveCollectionName(collectionName), "milvus disabled, fallback result for query: " + query, 0.0));
        }

        List<Float> vector = embed(query);
        String resolvedCollection = resolveCollectionName(collectionName);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("collectionName", resolvedCollection);
        payload.put("dbName", defaultDatabase());
        payload.put("data", List.of(vector));
        payload.put("annsField", milvusProperties.getVectorField());
        payload.put("limit", topK);
        payload.put("outputFields", List.of(
                milvusProperties.getPrimaryField(),
                milvusProperties.getTextField(),
                milvusProperties.getSourceTypeField(),
                milvusProperties.getSourceIdField()
        ));

        JsonNode response = post("/v2/vectordb/entities/search", payload);
        if (!isSuccess(response)) {
            throw new IllegalStateException("milvus search failed: " + extractMessage(response));
        }
        return parseSearchResults(response, resolvedCollection);
    }

    public List<Float> embed(String text) {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new IllegalStateException("EmbeddingModel bean not found. Configure a Spring AI embedding model before using Milvus RAG.");
        }
        float[] raw = embeddingModel.embed(text);
        List<Float> vector = new ArrayList<>(raw.length);
        for (float value : raw) {
            vector.add(value);
        }
        return vector;
    }

    public String resolveCollectionName(String requestedName) {
        return requestedName == null || requestedName.isBlank()
                ? milvusProperties.getDefaultCollection()
                : requestedName;
    }

    private List<Map<String, Object>> buildSchema(int dimension) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(Map.of(
                "fieldName", milvusProperties.getPrimaryField(),
                "dataType", "VarChar",
                "isPrimary", true,
                "elementTypeParams", Map.of("max_length", "128")
        ));
        fields.add(Map.of(
                "fieldName", milvusProperties.getSourceTypeField(),
                "dataType", "VarChar",
                "elementTypeParams", Map.of("max_length", "64")
        ));
        fields.add(Map.of(
                "fieldName", milvusProperties.getSourceIdField(),
                "dataType", "VarChar",
                "elementTypeParams", Map.of("max_length", "64")
        ));
        fields.add(Map.of(
                "fieldName", milvusProperties.getTextField(),
                "dataType", "VarChar",
                "elementTypeParams", Map.of("max_length", String.valueOf(milvusProperties.getMaxTextLength()))
        ));
        fields.add(Map.of(
                "fieldName", milvusProperties.getVectorField(),
                "dataType", "FloatVector",
                "elementTypeParams", Map.of("dim", String.valueOf(dimension))
        ));
        return fields;
    }

    private List<Map<String, Object>> buildIndexParams() {
        return List.of(Map.of(
                "fieldName", milvusProperties.getVectorField(),
                "indexName", milvusProperties.getVectorField() + "_idx",
                "metricType", milvusProperties.getMetricType(),
                "indexType", "AUTOINDEX"
        ));
    }

    private Map<String, Object> toMilvusEntity(MilvusVectorDocument document) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(milvusProperties.getPrimaryField(), document.documentId());
        row.put(milvusProperties.getSourceTypeField(), document.sourceType());
        row.put(milvusProperties.getSourceIdField(), document.sourceId());
        row.put(milvusProperties.getTextField(), document.content());
        row.put(milvusProperties.getVectorField(), document.vector());
        return row;
    }

    private JsonNode post(String uri, Object body) {
        return restClient.post()
                .uri(uri)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    private boolean isSuccess(JsonNode response) {
        if (response == null) {
            return false;
        }
        if (response.has("code")) {
            JsonNode code = response.get("code");
            if (code.isInt()) {
                return code.asInt() == 0 || code.asInt() == 200;
            }
            return "0".equals(code.asText()) || "200".equals(code.asText());
        }
        return true;
    }

    private boolean messageContains(JsonNode response, String text) {
        String message = extractMessage(response);
        return message != null && message.toLowerCase().contains(text.toLowerCase());
    }

    private String extractMessage(JsonNode response) {
        if (response == null) {
            return "empty response";
        }
        if (response.has("message")) {
            return response.get("message").asText();
        }
        if (response.has("msg")) {
            return response.get("msg").asText();
        }
        return response.toPrettyString();
    }

    private List<MilvusSearchResult> parseSearchResults(JsonNode response, String collectionName) {
        List<MilvusSearchResult> results = new ArrayList<>();
        JsonNode dataNode = response.get("data");
        if (dataNode == null || !dataNode.isArray()) {
            return results;
        }
        for (JsonNode item : dataNode) {
            String content = item.path(milvusProperties.getTextField()).asText("");
            double score = item.has("distance") ? item.get("distance").asDouble() : item.path("score").asDouble(0.0);
            results.add(new MilvusSearchResult(collectionName, content, score));
        }
        return results;
    }

    private int detectEmbeddingDimension() {
        return embed("dimension probe").size();
    }

    private String defaultDatabase() {
        return milvusProperties.getDatabase() == null || milvusProperties.getDatabase().isBlank()
                ? "default"
                : milvusProperties.getDatabase();
    }

    private String defaultToken(String token) {
        return token == null || token.isBlank() ? "root:Milvus" : token;
    }
}
