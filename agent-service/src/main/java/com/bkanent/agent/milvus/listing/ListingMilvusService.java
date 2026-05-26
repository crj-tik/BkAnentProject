package com.bkanent.agent.milvus.listing;

import com.bkanent.agent.config.AgentMilvusProperties;
import com.bkanent.agent.config.ListingRagProperties;
import com.bkanent.agent.milvus.core.MilvusDocumentStore;
import com.bkanent.agent.milvus.core.model.MilvusCollectionInitRequest;
import com.bkanent.agent.milvus.core.model.MilvusDeleteRequest;
import com.bkanent.agent.milvus.core.model.MilvusSearchRequest;
import com.bkanent.agent.milvus.core.model.MilvusSearchResult;
import com.bkanent.agent.milvus.core.model.MilvusUpsertRequest;
import com.bkanent.agent.milvus.core.model.MilvusVectorDocument;
import com.bkanent.agent.model.rag.ListingIndexRequest;
import com.bkanent.agent.model.rag.ListingRagMatch;
import com.bkanent.agent.model.rag.ListingRagQueryRequest;
import com.bkanent.agent.model.rag.ListingRagResponse;
import com.bkanent.common.model.KnowledgeDocument;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.model.ListingKeywordSearchResultDTO;
import com.bkanent.common.rpc.ListingMasterRpcService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ListingMilvusService 服务类。
 */
@Service
public class ListingMilvusService {

    /**
     * 字段：milvusDocumentStore。
     */
    private final MilvusDocumentStore milvusDocumentStore;
    /**
     * 字段：agentMilvusProperties。
     */
    private final AgentMilvusProperties agentMilvusProperties;
    /**
     * 字段：listingRagProperties。
     */
    private final ListingRagProperties listingRagProperties;
    /**
     * 字段：listingMasterRpcService。
     */
    private final ListingMasterRpcService listingMasterRpcService;
    /**
     * 字段：listingKeywordRecallService。
     */
    private final ListingKeywordRecallService listingKeywordRecallService;
    /**
     * 字段：listingRerankService。
     */
    private final ListingRerankService listingRerankService;

    /**
     * 构造 ListingMilvusService 实例。
     */
    public ListingMilvusService(MilvusDocumentStore milvusDocumentStore,
                                AgentMilvusProperties agentMilvusProperties,
                                ListingRagProperties listingRagProperties,
                                ListingKeywordRecallService listingKeywordRecallService,
                                ListingRerankService listingRerankService,
                                ObjectProvider<ListingMasterRpcService> listingMasterRpcServiceProvider) {
        this.milvusDocumentStore = milvusDocumentStore;
        this.agentMilvusProperties = agentMilvusProperties;
        this.listingRagProperties = listingRagProperties;
        this.listingKeywordRecallService = listingKeywordRecallService;
        this.listingRerankService = listingRerankService;
        this.listingMasterRpcService = listingMasterRpcServiceProvider.getIfAvailable();
    }

    /**
     * 初始化 Milvus 集合。
     */
    public void initializeCollection(String collectionName) {
        milvusDocumentStore.initializeCollection(new MilvusCollectionInitRequest(
                resolveCollectionName(collectionName),
                agentMilvusProperties.getEmbeddingDimension()
        ));
    }

    /**
     * 为房源建立向量索引。
     */
    public void indexListing(ListingIndexRequest request) {
        indexListing(request.listingId(), request.collectionName());
    }

    /**
     * 为房源建立向量索引。
     */
    public void indexListing(Long listingId, String collectionName) {
        KnowledgeDocument document = getListingKnowledgeDocument(listingId);
        if (document == null) {
            throw new IllegalArgumentException("Listing not found: " + listingId);
        }
        initializeCollection(collectionName);
        milvusDocumentStore.upsert(new MilvusUpsertRequest(
                resolveCollectionName(collectionName),
                List.of(toMilvusVectorDocument(document))
        ));
    }

    /**
     * 删除房源向量索引。
     */
    public void deleteListing(Long listingId, String collectionName) {
        if (listingId == null) {
            return;
        }
        milvusDocumentStore.delete(new MilvusDeleteRequest(
                resolveCollectionName(collectionName),
                List.of("listing:" + listingId)
        ));
    }

    /**
     * 执行双路召回并重排序。
     */
    public ListingRagResponse query(ListingRagQueryRequest request) {
        int targetTopK = resolveTopK(request.topK(), listingRagProperties.getRerankTopK());
        Map<Long, ListingRecallCandidate> merged = new LinkedHashMap<>();

        for (ListingKeywordSearchResultDTO keywordResult
                : listingKeywordRecallService.recall(request, resolveTopK(request.keywordTopK(), listingRagProperties.getKeywordTopK()))) {
            ListingDTO listing = keywordResult.listing();
            KnowledgeDocument document = getListingKnowledgeDocument(listing.id());
            ListingRecallCandidate candidate = merged.computeIfAbsent(
                    listing.id(),
                    key -> new ListingRecallCandidate(listing, resolveRecallContent(listing, document))
            );
            candidate.getRecallSources().add("KEYWORD");
            candidate.setKeywordScore(keywordResult.score());
        }

        List<MilvusSearchResult> vectorResults = milvusDocumentStore.search(new MilvusSearchRequest(
                resolveCollectionName(request.collectionName()),
                request.query(),
                resolveTopK(request.vectorTopK(), listingRagProperties.getVectorTopK()),
                listingRagProperties.getVectorSimilarityThreshold(),
                buildMilvusFilterExpression(request)
        ));
        for (MilvusSearchResult vectorResult : vectorResults) {
            Long listingId = parseListingId(vectorResult.sourceId());
            if (listingId == null) {
                continue;
            }
            ListingDTO listing = listingMasterRpcService == null ? null : listingMasterRpcService.getListingById(listingId);
            if (listing == null || !ListingStructuredFilter.matches(listing, request)) {
                continue;
            }
            ListingRecallCandidate candidate = merged.computeIfAbsent(
                    listingId,
                    key -> new ListingRecallCandidate(listing, vectorResult.content())
            );
            candidate.getRecallSources().add("VECTOR");
            candidate.setVectorScore(vectorResult.score());
        }

        List<ListingRecallCandidate> reranked = listingRerankService.rerank(request.query(), new ArrayList<>(merged.values()), targetTopK);
        String context = reranked.stream()
                .map(ListingRecallCandidate::getContent)
                .reduce((left, right) -> left + "\n---\n" + right)
                .orElse("");
        List<ListingRagMatch> matches = reranked.stream()
                .map(candidate -> new ListingRagMatch(
                        candidate.getListing().id(),
                        candidate.getListing().title(),
                        candidate.getListing().address(),
                        candidate.getListing().layout(),
                        candidate.getListing().area(),
                        candidate.getListing().totalPrice(),
                        new ArrayList<>(candidate.getRecallSources()),
                        candidate.getKeywordScore(),
                        candidate.getVectorScore(),
                        candidate.getRerankScore(),
                        candidate.getContent()
                ))
                .toList();
        return new ListingRagResponse(request.query(), context, matches);
    }

    /**
     * 解析 topK。
     */
    private int resolveTopK(Integer topK, int defaultTopK) {
        return topK == null ? defaultTopK : Math.max(1, topK);
    }

    /**
     * 构建 Milvus 过滤表达式。
     */
    private String buildMilvusFilterExpression(ListingRagQueryRequest request) {
        List<String> clauses = new ArrayList<>();
        if (StringUtils.hasText(request.layout())) {
            clauses.add("layout == \"" + escape(request.layout()) + "\"");
        }
        if (request.minArea() != null) {
            clauses.add("area >= " + request.minArea());
        }
        if (request.maxArea() != null) {
            clauses.add("area <= " + request.maxArea());
        }
        if (request.minTotalPrice() != null) {
            clauses.add("totalPrice >= " + request.minTotalPrice());
        }
        if (request.maxTotalPrice() != null) {
            clauses.add("totalPrice <= " + request.maxTotalPrice());
        }
        return clauses.isEmpty() ? null : String.join(" && ", clauses);
    }

    /**
     * 解析 listingId。
     */
    private Long parseListingId(String sourceId) {
        if (!StringUtils.hasText(sourceId)) {
            return null;
        }
        try {
            return Long.valueOf(sourceId);
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * 转义查询值。
     */
    private String escape(String value) {
        return value.replace("\"", "\\\"");
    }

    private KnowledgeDocument getListingKnowledgeDocument(Long listingId) {
        return listingMasterRpcService == null ? null : listingMasterRpcService.getListingKnowledgeDocument(listingId);
    }

    private MilvusVectorDocument toMilvusVectorDocument(KnowledgeDocument document) {
        return new MilvusVectorDocument(
                document.documentId(),
                document.bizType(),
                document.bizId(),
                document.content(),
                null,
                document.metadata()
        );
    }

    private String resolveRecallContent(ListingDTO listing, KnowledgeDocument document) {
        if (document != null && StringUtils.hasText(document.content())) {
            return document.content();
        }
        return "房源标题: " + listing.title()
                + "；地址: " + listing.address()
                + "；户型: " + listing.layout()
                + "；面积: " + listing.area()
                + "；总价: " + listing.totalPrice();
    }

    /**
     * 解析集合名称。
     */
    private String resolveCollectionName(String collectionName) {
        return StringUtils.hasText(collectionName) ? collectionName : agentMilvusProperties.getListingCollection();
    }
}
