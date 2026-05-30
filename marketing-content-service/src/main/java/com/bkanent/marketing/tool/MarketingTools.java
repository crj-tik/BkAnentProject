package com.bkanent.marketing.tool;

import com.bkanent.marketing.model.MarketingContentDetailResponse;
import com.bkanent.marketing.model.MarketingContentSearchRequest;
import com.bkanent.marketing.model.MarketingContentUpsertRequest;
import com.bkanent.marketing.model.MarketingPublishStatusUpdateRequest;
import com.bkanent.marketing.service.MarketingAssetService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class MarketingTools {

    private final MarketingAssetService marketingAssetService;

    public MarketingTools(MarketingAssetService marketingAssetService) {
        this.marketingAssetService = marketingAssetService;
    }

    @Tool(description = "Create marketing content for a listing. Saves the content and returns the detail with content ID.")
    public MarketingContentDetailResponse createMarketingContent(
            @ToolParam(description = "Listing ID") Long listingId,
            @ToolParam(description = "Target platform, e.g. DOUYIN, WECHAT, XIAOHONGSHU") String platform,
            @ToolParam(description = "Content title") String title,
            @ToolParam(description = "Content type: TEXT, IMAGE, VIDEO") String contentType,
            @ToolParam(description = "The actual marketing copywriting text") String copywriting,
            @ToolParam(description = "Cover image URL (optional)") String coverImageUrl,
            @ToolParam(description = "Video URL (optional)") String videoUrl) {
        return marketingAssetService.createContent(new MarketingContentUpsertRequest(
                listingId, platform, title, contentType, copywriting, List.of(),
                coverImageUrl, videoUrl, "DEFAULT", List.of("agent-generated"), "APPROVED", null));
    }

    @Tool(description = "Publish a marketing content by its ID. Updates the publish status and returns the result.")
    public MarketingContentDetailResponse publishContent(
            @ToolParam(description = "Content ID to publish") Long contentId,
            @ToolParam(description = "Publish status: SUCCESS, FAILED") String publishStatus) {
        return marketingAssetService.updatePublishStatus(contentId, new MarketingPublishStatusUpdateRequest(
                publishStatus != null ? publishStatus : "SUCCESS",
                "Published by marketing-agent",
                "pub-" + UUID.randomUUID(),
                LocalDateTime.now()));
    }

    @Tool(description = "Search marketing contents by keyword, listing ID, platform, or content type.")
    public List<MarketingContentDetailResponse> searchContents(
            @ToolParam(description = "Search keyword") String keyword,
            @ToolParam(description = "Listing ID filter (optional, pass 0 to skip)") Long listingId,
            @ToolParam(description = "Platform filter, e.g. DOUYIN. Pass empty string to skip.") String platform) {
        return marketingAssetService.searchContents(new MarketingContentSearchRequest(
                listingId != null && listingId > 0 ? listingId : null,
                platform == null || platform.isBlank() ? null : platform,
                keyword,
                null, null, null, null));
    }
}