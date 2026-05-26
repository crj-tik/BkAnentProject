package com.bkanent.agent.milvus.listing;

import java.util.List;

/**
 * ListingRerankService 服务类。
 */
public interface ListingRerankService {

    List<ListingRecallCandidate> rerank(String query, List<ListingRecallCandidate> candidates, int topK);
}
