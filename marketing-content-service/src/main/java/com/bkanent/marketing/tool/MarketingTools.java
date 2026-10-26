package com.bkanent.marketing.tool;

import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.marketing.campaign.CampaignPlanPrepareRequest;
import com.bkanent.common.marketing.campaign.CampaignPlanResponse;
import com.bkanent.common.marketing.campaign.CampaignTargetRequest;
import com.bkanent.common.rpc.ListingMasterRpcService;
import com.bkanent.marketing.campaign.CampaignLifecycleService;
import com.bkanent.marketing.campaign.CampaignDomainException;
import com.bkanent.marketing.config.MarketingAgentProperties;
import com.bkanent.marketing.model.MarketingContentDetailResponse;
import com.bkanent.marketing.model.MarketingContentSearchRequest;
import com.bkanent.marketing.model.MarketingContentUpsertRequest;
import com.bkanent.marketing.service.MarketingAgentService;
import com.bkanent.marketing.service.MarketingAssetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

@Component
public class MarketingTools {

    private static final Logger log = LoggerFactory.getLogger(MarketingTools.class);

    private final MarketingAssetService marketingAssetService;
    private final ObjectProvider<MarketingAgentService> marketingAgentServiceProvider;
    private final MarketingAgentProperties properties;
    private final com.bkanent.marketing.service.MarketingListingReadClient listingReadClient;
    private final CampaignLifecycleService campaignLifecycleService;
    private final ObjectMapper objectMapper;

    public MarketingTools(MarketingAssetService marketingAssetService,
                          ObjectProvider<MarketingAgentService> marketingAgentServiceProvider,
                          MarketingAgentProperties properties,
                          com.bkanent.marketing.service.MarketingListingReadClient listingReadClient,
                          CampaignLifecycleService campaignLifecycleService,
                          ObjectMapper objectMapper) {
        this.marketingAssetService = marketingAssetService;
        this.marketingAgentServiceProvider = marketingAgentServiceProvider;
        this.properties = properties;
        this.listingReadClient = listingReadClient;
        this.campaignLifecycleService = campaignLifecycleService;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "Create marketing content for a listing. Saves the content and returns the detail with content ID. "
            + "If copywriting is omitted or empty, an LLM generates the copy from the listing info and target platform.")
    public MarketingContentDetailResponse createMarketingContent(
            @ToolParam(description = "Listing ID") Long listingId,
            @ToolParam(description = "Target platform, e.g. DOUYIN, WECHAT, XIAOHONGSHU") String platform,
            @ToolParam(description = "Content title") String title,
            @ToolParam(description = "Content type: TEXT, IMAGE, VIDEO") String contentType,
            @ToolParam(description = "The marketing copywriting text. Pass empty string to let the LLM generate it from the listing info.", required = false)
            String copywriting,
            @ToolParam(description = "Cover image URL (optional)") String coverImageUrl,
            @ToolParam(description = "Video URL (optional)") String videoUrl) {
        String source = "manual";
        if (copywriting == null || copywriting.isBlank()) {
            copywriting = generateCopy(listingId, platform);
            source = "llm";
        }
        return marketingAssetService.createContent(new MarketingContentUpsertRequest(
                listingId, platform, title, contentType, copywriting, List.of(),
                coverImageUrl, videoUrl, "DEFAULT", List.of("agent-generated"), "DRAFT", null, source));
    }

    private String generateCopy(Long listingId, String platform) {
        if (!properties.isLlmGenerationEnabled()) {
            throw new IllegalStateException("LLM 文案生成未开启，请提供文案内容");
        }
        String listingSummary = buildListingSummary(listingId);
        log.info("Generating marketing copy for listing {} on platform {} via LLM", listingId, platform);
        return marketingAgentServiceProvider.getObject().generateCopy(listingSummary, platform);
    }

    private String buildListingSummary(Long listingId) {
        if (listingId == null || listingReadClient == null) {
            throw new IllegalArgumentException("无法获取房源信息，请先提供房源 ID 或直接给出文案");
        }
        ListingDTO listing = listingReadClient.get(listingId);
        if (listing == null) {
            throw new IllegalArgumentException("房源不存在或不可用: " + listingId);
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("标题: ").append(safe(listing.title())).append('\n');
        sb.append("地址: ").append(safe(listing.address())).append('\n');
        sb.append("户型: ").append(safe(listing.layout())).append('\n');
        sb.append("面积: ").append(listing.area() == null ? "待补充" : listing.area().toPlainString() + "㎡").append('\n');
        sb.append("总价: ").append(listing.totalPrice() == null ? "待补充" : listing.totalPrice().toPlainString() + "万元").append('\n');
        sb.append("楼层: ").append(safe(listing.floorLevel())).append('\n');
        sb.append("装修: ").append(safe(listing.decoration())).append('\n');
        sb.append("学区: ").append(safe(listing.schoolZone())).append('\n');
        sb.append("交通: ").append(safe(listing.traffic()));
        return sb.toString();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "待补充" : value;
    }

    @Tool(description = "Prepare an authenticated campaign plan for an approved marketing content. This does not publish; a user must confirm the exact plan snapshot.")
    public CampaignPlanResponse prepareMarketingCampaign(
            @ToolParam(description = "Approved marketing content ID") Long contentId,
            @ToolParam(description = "JSON array of targets: platform, accountId, optional arguments") String targetsJson,
            @ToolParam(description = "Comma-separated brand asset IDs, or empty") String brandAssetIds) {
        try {
            List<CampaignTargetRequest> targets = objectMapper.readValue(targetsJson, new TypeReference<>() { });
            List<Long> assets = brandAssetIds == null || brandAssetIds.isBlank() ? List.of()
                    : java.util.Arrays.stream(brandAssetIds.split(",")).map(String::trim).map(Long::parseLong).toList();
            return campaignLifecycleService.prepare(new CampaignPlanPrepareRequest(contentId, assets, targets, null));
        } catch (CampaignDomainException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("targetsJson or brandAssetIds is invalid", failure);
        }
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
