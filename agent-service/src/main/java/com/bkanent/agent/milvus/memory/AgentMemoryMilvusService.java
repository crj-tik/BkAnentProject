package com.bkanent.agent.milvus.memory;

import com.bkanent.agent.config.AgentMilvusProperties;
import com.bkanent.agent.milvus.core.MilvusDocumentStore;
import com.bkanent.agent.milvus.core.model.MilvusCollectionInitRequest;
import com.bkanent.agent.milvus.core.model.MilvusSearchRequest;
import com.bkanent.agent.milvus.core.model.MilvusSearchResult;
import com.bkanent.agent.milvus.core.model.MilvusUpsertRequest;
import com.bkanent.agent.milvus.core.model.MilvusVectorDocument;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * AgentMemoryMilvusService 服务类。
 */
@Service
public class AgentMemoryMilvusService {

    /**
     * 字段：milvusDocumentStore。
     */
    private final MilvusDocumentStore milvusDocumentStore;
    /**
     * 字段：agentMilvusProperties。
     */
    private final AgentMilvusProperties agentMilvusProperties;

    /**
     * 构造 AgentMemoryMilvusService 实例。
     */
    public AgentMemoryMilvusService(MilvusDocumentStore milvusDocumentStore,
                                    AgentMilvusProperties agentMilvusProperties) {
        this.milvusDocumentStore = milvusDocumentStore;
        this.agentMilvusProperties = agentMilvusProperties;
    }

    /**
     * 初始化ializeMemoryCollection。
     */
    public void initializeMemoryCollection(String collectionName) {
        milvusDocumentStore.initializeCollection(new MilvusCollectionInitRequest(
                resolveCollectionName(collectionName),
                agentMilvusProperties.getEmbeddingDimension()
        ));
    }

    /**
     * 写入或更新memory。
     */
    public void upsertMemory(MilvusUpsertRequest request) {
        milvusDocumentStore.upsert(new MilvusUpsertRequest(
                resolveCollectionName(request.collectionName()),
                request.documents()
        ));
    }

    /**
     * 检索knowledge。
     */
    public List<MilvusSearchResult> searchKnowledge(String collectionName, String query, int topK) {
        return milvusDocumentStore.search(new MilvusSearchRequest(
                resolveCollectionName(collectionName),
                query,
                topK,
                0.0D,
                null
        ));
    }

    /**
     * 获取默认memoryCollection。
     */
    public String defaultMemoryCollection() {
        return agentMilvusProperties.getDefaultCollection();
    }

    /**
     * 便捷方法：按 documentId 写入或更新文本内容及元数据。
     */
    public void upsertMemory(String collectionName, String documentId, String content, Map<String, Object> metadata) {
        MilvusVectorDocument doc = new MilvusVectorDocument(
                documentId, "memory", documentId, content, null,
                metadata != null ? metadata : Map.of()
        );
        upsertMemory(new MilvusUpsertRequest(collectionName, List.of(doc)));
    }

    /**
     * 解析collectionName。
     */
    private String resolveCollectionName(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            return agentMilvusProperties.getDefaultCollection();
        }
        return collectionName;
    }
}
