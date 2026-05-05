package com.bkanent.agent.mcp;

import java.util.List;

public interface MilvusMcpTool {

    void initializeCollection(MilvusCollectionInitRequest request);

    void upsert(MilvusUpsertRequest request);

    List<MilvusSearchResult> search(String collectionName, String query, int topK);

    default List<MilvusSearchResult> search(String query, int topK) {
        return search(null, query, topK);
    }
}
