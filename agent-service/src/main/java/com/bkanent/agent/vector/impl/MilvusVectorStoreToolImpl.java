package com.bkanent.agent.vector.impl;

import com.bkanent.agent.config.AgentMilvusProperties;
import com.bkanent.agent.config.MilvusConnectionProperties;
import com.bkanent.agent.model.vector.MilvusCollectionInitRequest;
import com.bkanent.agent.model.vector.MilvusSearchResult;
import com.bkanent.agent.model.vector.MilvusUpsertRequest;
import com.bkanent.agent.model.vector.MilvusVectorDocument;
import com.bkanent.agent.vector.MilvusVectorStoreTool;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Milvus 向量库工具实现。
 */
@Component
public class MilvusVectorStoreToolImpl implements MilvusVectorStoreTool {

    private static final Map<String, List<MilvusVectorDocument>> COLLECTION_STORE = new ConcurrentHashMap<>();

    private final MilvusConnectionProperties milvusConnectionProperties;
    private final AgentMilvusProperties agentMilvusProperties;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    public MilvusVectorStoreToolImpl(MilvusConnectionProperties milvusConnectionProperties,
                                     AgentMilvusProperties agentMilvusProperties,
                                     ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.milvusConnectionProperties = milvusConnectionProperties;
        this.agentMilvusProperties = agentMilvusProperties;
        this.embeddingModelProvider = embeddingModelProvider;
    }

    @Override
    public void initializeCollection(MilvusCollectionInitRequest request) {
        String collectionName = normalizeCollectionName(request.collectionName());
        COLLECTION_STORE.computeIfAbsent(collectionName, key -> new ArrayList<>());
    }

    @Override
    public void upsert(MilvusUpsertRequest request) {
        String collectionName = normalizeCollectionName(request.collectionName());
        initializeCollection(new MilvusCollectionInitRequest(collectionName, null));
        List<MilvusVectorDocument> documents = COLLECTION_STORE.get(collectionName);
        Map<String, MilvusVectorDocument> merged = new LinkedHashMap<>();
        for (MilvusVectorDocument document : documents) {
            merged.put(document.documentId(), document);
        }
        if (request.documents() != null) {
            for (MilvusVectorDocument document : request.documents()) {
                merged.put(document.documentId(), document);
            }
        }
        documents.clear();
        documents.addAll(merged.values());
    }

    @Override
    public List<MilvusSearchResult> search(String collectionName, String query, int topK) {
        String normalizedCollectionName = normalizeCollectionName(collectionName);
        List<MilvusVectorDocument> documents = COLLECTION_STORE.getOrDefault(normalizedCollectionName, List.of());
        if (documents.isEmpty()) {
            return List.of();
        }
        List<Float> queryVector = embed(query == null ? "" : query);
        return documents.stream()
                .map(document -> new MilvusSearchResult(
                        normalizedCollectionName,
                        document.content(),
                        cosineSimilarity(queryVector, document.vector())
                ))
                .sorted(Comparator.comparing(MilvusSearchResult::score, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(1, topK))
                .toList();
    }

    public List<Float> embed(String content) {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel != null) {
            float[] embedded = embeddingModel.embed(new Document(truncate(content)));
            List<Float> vector = new ArrayList<>(embedded.length);
            for (float value : embedded) {
                vector.add(value);
            }
            return vector;
        }
        return fallbackEmbed(content);
    }

    private List<Float> fallbackEmbed(String content) {
        String normalized = truncate(content == null ? "" : content);
        List<Float> vector = new ArrayList<>(16);
        for (int index = 0; index < 16; index++) {
            int sourceIndex = normalized.isEmpty() ? 0 : index % normalized.length();
            int codePoint = normalized.isEmpty() ? 0 : normalized.charAt(sourceIndex);
            vector.add((float) (codePoint % 97) / 100.0F);
        }
        return vector;
    }

    private Double cosineSimilarity(List<Float> left, List<Float> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0D;
        }
        int length = Math.min(left.size(), right.size());
        double dot = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int index = 0; index < length; index++) {
            double leftValue = left.get(index);
            double rightValue = right.get(index);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm == 0D || rightNorm == 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private String normalizeCollectionName(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            return agentMilvusProperties.getDefaultCollection();
        }
        return collectionName;
    }

    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        int maxLength = agentMilvusProperties.getMaxTextLength() == null ? 8192 : agentMilvusProperties.getMaxTextLength();
        return content.length() <= maxLength ? content : content.substring(0, maxLength);
    }
}
