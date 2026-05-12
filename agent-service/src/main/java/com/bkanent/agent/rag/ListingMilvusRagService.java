package com.bkanent.agent.rag;

import com.bkanent.agent.model.rag.ListingIndexRequest;
import com.bkanent.agent.model.rag.ListingRagQueryRequest;
import com.bkanent.agent.model.rag.ListingRagResponse;
import com.bkanent.agent.model.vector.MilvusCollectionInitRequest;
import com.bkanent.agent.model.vector.MilvusSearchResult;
import com.bkanent.agent.model.vector.MilvusUpsertRequest;
import com.bkanent.agent.model.vector.MilvusVectorDocument;
import com.bkanent.agent.vector.MilvusVectorStoreTool;
import com.bkanent.agent.vector.impl.MilvusVectorStoreToolImpl;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.rpc.ListingMasterRpcService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 房源 Milvus RAG 服务。
 */
@Service
public class ListingMilvusRagService {

    private final MilvusVectorStoreTool milvusVectorStoreTool;
    private final MilvusVectorStoreToolImpl milvusVectorStoreToolImpl;
    private final ListingMasterRpcService listingMasterRpcService;

    public ListingMilvusRagService(MilvusVectorStoreTool milvusVectorStoreTool,
                                   MilvusVectorStoreToolImpl milvusVectorStoreToolImpl,
                                   ObjectProvider<ListingMasterRpcService> listingMasterRpcServiceProvider) {
        this.milvusVectorStoreTool = milvusVectorStoreTool;
        this.milvusVectorStoreToolImpl = milvusVectorStoreToolImpl;
        this.listingMasterRpcService = listingMasterRpcServiceProvider.getIfAvailable();
    }

    public void initializeCollection(String collectionName) {
        milvusVectorStoreTool.initializeCollection(new MilvusCollectionInitRequest(collectionName, null));
    }

    public void indexListing(ListingIndexRequest request) {
        ListingDTO listing = listingMasterRpcService == null ? null : listingMasterRpcService.getListingById(request.listingId());
        if (listing == null) {
            throw new IllegalArgumentException("未找到房源: " + request.listingId());
        }

        String content = buildListingDocument(listing);
        List<Float> vector = milvusVectorStoreToolImpl.embed(content);
        initializeCollection(request.collectionName());
        milvusVectorStoreTool.upsert(new MilvusUpsertRequest(
                request.collectionName(),
                List.of(new MilvusVectorDocument(
                        "listing:" + listing.id(),
                        "LISTING",
                        String.valueOf(listing.id()),
                        content,
                        vector
                ))
        ));
    }

    public ListingRagResponse query(ListingRagQueryRequest request) {
        List<MilvusSearchResult> matches = milvusVectorStoreTool.search(
                request.collectionName(),
                request.query(),
                request.topK() == null ? 5 : request.topK()
        );
        String context = matches.stream()
                .map(MilvusSearchResult::content)
                .reduce((left, right) -> left + "\n---\n" + right)
                .orElse("");
        return new ListingRagResponse(request.query(), context, matches);
    }

    private String buildListingDocument(ListingDTO listing) {
        return "房源标题：" + listing.title()
                + "；地址：" + listing.address()
                + "；户型：" + listing.layout()
                + "；面积：" + listing.area()
                + "；总价：" + listing.totalPrice()
                + "；状态：" + listing.status();
    }
}
