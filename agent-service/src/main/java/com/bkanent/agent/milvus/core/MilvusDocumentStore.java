package com.bkanent.agent.milvus.core;

import com.bkanent.agent.milvus.core.model.MilvusCollectionInitRequest;
import com.bkanent.agent.milvus.core.model.MilvusDeleteRequest;
import com.bkanent.agent.milvus.core.model.MilvusSearchRequest;
import com.bkanent.agent.milvus.core.model.MilvusSearchResult;
import com.bkanent.agent.milvus.core.model.MilvusUpsertRequest;

import java.util.List;

/**
 * MilvusDocumentStore 接口定义。
 */
public interface MilvusDocumentStore {

    void initializeCollection(MilvusCollectionInitRequest request);

    void upsert(MilvusUpsertRequest request);

    void delete(MilvusDeleteRequest request);

    List<MilvusSearchResult> search(MilvusSearchRequest request);
}
