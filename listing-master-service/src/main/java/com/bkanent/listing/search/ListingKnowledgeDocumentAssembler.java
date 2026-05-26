package com.bkanent.listing.search;

import com.bkanent.common.model.KnowledgeDocument;
import com.bkanent.common.model.ListingDTO;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ListingKnowledgeDocumentAssembler 房源知识文档组装器。
 */
@Component
public class ListingKnowledgeDocumentAssembler {

    public KnowledgeDocument assemble(ListingDTO listing) {
        return new KnowledgeDocument(
                "listing:" + listing.id(),
                "LISTING",
                String.valueOf(listing.id()),
                listing.title(),
                buildContent(listing),
                buildMetadata(listing)
        );
    }

    private Map<String, Object> buildMetadata(ListingDTO listing) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("listingId", listing.id());
        metadata.put("title", listing.title());
        metadata.put("address", listing.address());
        metadata.put("region", listing.address());
        metadata.put("layout", listing.layout());
        metadata.put("area", listing.area());
        metadata.put("totalPrice", listing.totalPrice());
        metadata.put("status", listing.status());
        metadata.put("floorLevel", listing.floorLevel());
        metadata.put("decoration", listing.decoration());
        metadata.put("schoolZone", listing.schoolZone());
        metadata.put("traffic", listing.traffic());
        metadata.put("verificationStatus", listing.verificationStatus());
        return metadata;
    }

    private String buildContent(ListingDTO listing) {
        return "房源标题: " + listing.title()
                + "；地址: " + listing.address()
                + "；户型: " + listing.layout()
                + "；面积: " + listing.area()
                + "；总价: " + listing.totalPrice()
                + "；状态: " + listing.status()
                + "；楼层: " + listing.floorLevel()
                + "；装修: " + listing.decoration()
                + "；学区: " + listing.schoolZone()
                + "；交通: " + listing.traffic()
                + "；核验状态: " + listing.verificationStatus();
    }
}
