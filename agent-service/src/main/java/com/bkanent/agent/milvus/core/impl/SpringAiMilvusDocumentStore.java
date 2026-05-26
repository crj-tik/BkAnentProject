package com.bkanent.agent.milvus.core.impl;

import com.bkanent.agent.config.AgentMilvusProperties;
import com.bkanent.agent.config.MilvusConnectionProperties;
import com.bkanent.agent.milvus.core.MilvusDocumentStore;
import com.bkanent.agent.milvus.core.model.MilvusCollectionInitRequest;
import com.bkanent.agent.milvus.core.model.MilvusDeleteRequest;
import com.bkanent.agent.milvus.core.model.MilvusSearchRequest;
import com.bkanent.agent.milvus.core.model.MilvusSearchResult;
import com.bkanent.agent.milvus.core.model.MilvusUpsertRequest;
import com.bkanent.agent.milvus.core.model.MilvusVectorDocument;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.IndexType;
import io.milvus.param.ConnectParam;
import io.milvus.param.MetricType;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * SpringAiMilvusDocumentStore 组件。
 */
@Component
public class SpringAiMilvusDocumentStore implements MilvusDocumentStore {

    /**
     * 字段：METADATA_DOC_ID。
     */
    private static final String METADATA_DOC_ID = "documentId";

    /**
     * 字段：milvusConnectionProperties。
     */
    private final MilvusConnectionProperties milvusConnectionProperties;
    /**
     * 字段：agentMilvusProperties。
     */
    private final AgentMilvusProperties agentMilvusProperties;
    /**
     * 字段：embeddingModelProvider。
     */
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final ConcurrentMap<String, VectorStore> vectorStores = new ConcurrentHashMap<>();

    /**
     * 字段：milvusServiceClient。
     */
    private volatile MilvusServiceClient milvusServiceClient;

    /**
     * 构造 SpringAiMilvusDocumentStore 实例。
     */
    public SpringAiMilvusDocumentStore(MilvusConnectionProperties milvusConnectionProperties,
                                       AgentMilvusProperties agentMilvusProperties,
                                       ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.milvusConnectionProperties = milvusConnectionProperties;
        this.agentMilvusProperties = agentMilvusProperties;
        this.embeddingModelProvider = embeddingModelProvider;
    }

    /**
     * 初始化ializeCollection。
     */
    @Override
    public void initializeCollection(MilvusCollectionInitRequest request) {
        vectorStore(resolveCollectionName(request.collectionName()), request.dimension());
    }

    /**
     * 写入或更新。
     */
    @Override
    public void upsert(MilvusUpsertRequest request) {
        VectorStore vectorStore = vectorStore(resolveCollectionName(request.collectionName()), null);
        List<Document> documents = request.documents() == null ? List.of() : request.documents().stream()
                .map(this::toDocument)
                .toList();
        if (!documents.isEmpty()) {
            vectorStore.add(documents);
        }
    }

    /**
     * 删除文档。
     */
    @Override
    public void delete(MilvusDeleteRequest request) {
        List<String> documentIds = request.documentIds() == null ? List.of() : request.documentIds().stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (!documentIds.isEmpty()) {
            vectorStore(resolveCollectionName(request.collectionName()), null).delete(documentIds);
        }
    }

    /**
     * 检索。
     */
    @Override
    public List<MilvusSearchResult> search(MilvusSearchRequest request) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(request.query())
                .topK(Math.max(1, request.topK()));
        if (request.similarityThreshold() != null) {
            builder.similarityThreshold(request.similarityThreshold());
        }
        if (StringUtils.hasText(request.nativeFilterExpression())) {
            builder.filterExpression(request.nativeFilterExpression());
        }
        return vectorStore(resolveCollectionName(request.collectionName()), null)
                .similaritySearch(builder.build())
                .stream()
                .map(document -> new MilvusSearchResult(
                        resolveCollectionName(request.collectionName()),
                        metadataAsString(document, METADATA_DOC_ID, document.getId()),
                        metadataAsString(document, agentMilvusProperties.getSourceTypeField(), null),
                        metadataAsString(document, agentMilvusProperties.getSourceIdField(), null),
                        document.getText(),
                        document.getScore(),
                        document.getMetadata()
                ))
                .toList();
    }

    /**
     * 转换document。
     */
    private Document toDocument(MilvusVectorDocument document) {
        Map<String, Object> metadata = new HashMap<>();
        if (document.metadata() != null) {
            metadata.putAll(document.metadata());
        }
        metadata.put(agentMilvusProperties.getSourceTypeField(), document.sourceType());
        metadata.put(agentMilvusProperties.getSourceIdField(), document.sourceId());
        metadata.put(METADATA_DOC_ID, document.documentId());
        return Document.builder()
                .id(document.documentId())
                .text(document.content())
                .metadata(metadata)
                .build();
    }

    /**
     * 处理vectorStore。
     */
    private VectorStore vectorStore(String collectionName, Integer dimension) {
        return vectorStores.computeIfAbsent(collectionName, key -> MilvusVectorStore.builder(client(), embeddingModel())
                .databaseName(resolveDatabaseName())
                .collectionName(key)
                .embeddingDimension(resolveDimension(dimension))
                .metricType(MetricType.valueOf(agentMilvusProperties.getMetricType()))
                .indexType(IndexType.valueOf(agentMilvusProperties.getIndexType()))
                .indexParameters(agentMilvusProperties.getIndexParameters())
                .initializeSchema(true)
                .autoId(false)
                .iDFieldName(agentMilvusProperties.getPrimaryField())
                .contentFieldName(agentMilvusProperties.getTextField())
                .metadataFieldName(agentMilvusProperties.getMetadataField())
                .embeddingFieldName(agentMilvusProperties.getVectorField())
                .build());
    }

    /**
     * 处理client。
     */
    private MilvusServiceClient client() {
        MilvusServiceClient current = milvusServiceClient;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (milvusServiceClient == null) {
                milvusServiceClient = new MilvusServiceClient(connectParam());
            }
            return milvusServiceClient;
        }
    }

    /**
     * 处理connectParam。
     */
    private ConnectParam connectParam() {
        if (StringUtils.hasText(milvusConnectionProperties.getUri())) {
            return ConnectParam.newBuilder()
                    .withUri(milvusConnectionProperties.getUri())
                    .withAuthorization(milvusConnectionProperties.getToken())
                    .build();
        }
        URI endpoint = URI.create(milvusConnectionProperties.getEndpoint());
        return ConnectParam.newBuilder()
                .withHost(endpoint.getHost())
                .withPort(endpoint.getPort())
                .withAuthorization(milvusConnectionProperties.getToken())
                .build();
    }

    /**
     * 处理embeddingModel。
     */
    private EmbeddingModel embeddingModel() {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new IllegalStateException("EmbeddingModel bean is required for real Milvus retrieval.");
        }
        return embeddingModel;
    }

    /**
     * 解析dimension。
     */
    private int resolveDimension(Integer dimension) {
        if (dimension != null && dimension > 0) {
            return dimension;
        }
        if (agentMilvusProperties.getEmbeddingDimension() != null && agentMilvusProperties.getEmbeddingDimension() > 0) {
            return agentMilvusProperties.getEmbeddingDimension();
        }
        throw new IllegalArgumentException("Milvus embeddingDimension must be configured.");
    }

    /**
     * 解析collectionName。
     */
    private String resolveCollectionName(String collectionName) {
        if (StringUtils.hasText(collectionName)) {
            return collectionName;
        }
        return agentMilvusProperties.getDefaultCollection();
    }

    /**
     * 解析databaseName。
     */
    private String resolveDatabaseName() {
        return StringUtils.hasText(milvusConnectionProperties.getDatabase())
                ? milvusConnectionProperties.getDatabase()
                : "default";
    }

    /**
     * 处理metadataAsString。
     */
    private String metadataAsString(Document document, String key, String defaultValue) {
        Object value = document.getMetadata() == null ? null : document.getMetadata().get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }
}
