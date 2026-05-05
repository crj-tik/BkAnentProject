package com.bkanent.agent.rag;

import com.bkanent.agent.mcp.MilvusCollectionInitRequest;
import com.bkanent.agent.mcp.MilvusMcpTool;
import com.bkanent.agent.mcp.MilvusSearchResult;
import com.bkanent.agent.mcp.MilvusUpsertRequest;
import com.bkanent.agent.mcp.MilvusVectorDocument;
import com.bkanent.agent.mcp.MilvusMcpToolImpl;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.rpc.ListingMasterRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ListingMilvusRagService {

    @DubboReference(check = false)
    private ListingMasterRpcService listingMasterRpcService;

    private final MilvusMcpTool milvusMcpTool;
    private final MilvusMcpToolImpl milvusMcpToolImpl;

    public ListingMilvusRagService(MilvusMcpTool milvusMcpTool, MilvusMcpToolImpl milvusMcpToolImpl) {
        this.milvusMcpTool = milvusMcpTool;
        this.milvusMcpToolImpl = milvusMcpToolImpl;
    }

    public void initializeCollection(String collectionName) {
        milvusMcpTool.initializeCollection(new MilvusCollectionInitRequest(collectionName, null));
    }

    public void indexListing(ListingIndexRequest request) {
        ListingDTO listing = listingMasterRpcService == null ? null : listingMasterRpcService.getListingById(request.listingId());
        if (listing == null) {
            throw new IllegalArgumentException("listing not found: " + request.listingId());
        }

        String content = buildListingDocument(listing);
        List<Float> vector = milvusMcpToolImpl.embed(content);
        initializeCollection(request.collectionName());
        milvusMcpTool.upsert(new MilvusUpsertRequest(
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
        List<MilvusSearchResult> matches = milvusMcpTool.search(request.collectionName(), request.query(), request.topK() == null ? 5 : request.topK());
        String context = matches.stream().map(MilvusSearchResult::content).reduce((left, right) -> left + "\n---\n" + right).orElse("");
        return new ListingRagResponse(request.query(), context, matches);
    }

    private String buildListingDocument(ListingDTO listing) {
        return "Listing Title: " + listing.title() +
                "; Address: " + listing.address() +
                "; Layout: " + listing.layout() +
                "; Area: " + listing.area() +
                "; Total Price: " + listing.totalPrice() +
                "; Status: " + listing.status();
    }
}
