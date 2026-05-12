package com.bkanent.agent.vector;

import com.bkanent.agent.model.vector.MilvusCollectionInitRequest;
import com.bkanent.agent.model.vector.MilvusSearchResult;
import com.bkanent.agent.model.vector.MilvusUpsertRequest;

import java.util.List;

/**
 * Milvus 向量库工具抽象。
 *
 * <p>该接口是项目内部的本地工具抽象，用于统一封装 Milvus 集合初始化、
 * 向量写入和相似度检索能力，不代表 Spring AI 官方 MCP 协议。</p>
 */
public interface MilvusVectorStoreTool {

    /**
     * 初始化向量集合。
     *
     * @param request 集合初始化请求
     */
    void initializeCollection(MilvusCollectionInitRequest request);

    /**
     * 向量写入或更新。
     *
     * @param request 向量写入请求
     */
    void upsert(MilvusUpsertRequest request);

    /**
     * 按集合执行相似度检索。
     *
     * @param collectionName 集合名称
     * @param query 检索问题
     * @param topK 返回条数
     * @return 检索结果
     */
    List<MilvusSearchResult> search(String collectionName, String query, int topK);

    /**
     * 在默认集合中执行相似度检索。
     *
     * @param query 检索问题
     * @param topK 返回条数
     * @return 检索结果
     */
    default List<MilvusSearchResult> search(String query, int topK) {
        return search(null, query, topK);
    }
}
