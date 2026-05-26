package com.bkanent.agent.milvus.core.impl;

import com.bkanent.agent.config.AgentMilvusProperties;
import com.bkanent.agent.milvus.core.MilvusEmbeddingService;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * DefaultMilvusEmbeddingService 服务类。
 */
@Component
public class DefaultMilvusEmbeddingService implements MilvusEmbeddingService {

    /**
     * 字段：agentMilvusProperties。
     */
    private final AgentMilvusProperties agentMilvusProperties;
    /**
     * 字段：embeddingModelProvider。
     */
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    /**
     * 构造 DefaultMilvusEmbeddingService 实例。
     */
    public DefaultMilvusEmbeddingService(AgentMilvusProperties agentMilvusProperties,
                                         ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.agentMilvusProperties = agentMilvusProperties;
        this.embeddingModelProvider = embeddingModelProvider;
    }

    /**
     * 处理embed。
     */
    @Override
    public List<Float> embed(String content) {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new IllegalStateException("EmbeddingModel bean is required for Milvus indexing and retrieval.");
        }
        float[] embedded = embeddingModel.embed(new Document(truncate(content)));
        List<Float> vector = new ArrayList<>(embedded.length);
        for (float value : embedded) {
            vector.add(value);
        }
        return vector;
    }

    /**
     * 处理truncate。
     */
    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        int maxLength = agentMilvusProperties.getMaxTextLength() == null ? 8192 : agentMilvusProperties.getMaxTextLength();
        return content.length() <= maxLength ? content : content.substring(0, maxLength);
    }
}
