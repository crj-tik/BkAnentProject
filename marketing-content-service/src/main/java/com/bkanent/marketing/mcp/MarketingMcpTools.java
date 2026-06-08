package com.bkanent.marketing.mcp;

import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.common.rpc.MediaWorkerRpcService;
import com.bkanent.common.rpc.PromotionRpcService;
import com.bkanent.common.tool.McpTool;
import com.bkanent.marketing.service.MarketingAssetService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class MarketingMcpTools implements McpTool {

    private final MarketingAssetService marketingAssetService;
    private final MediaWorkerRpcService mediaWorkerRpcService;
    private final PromotionRpcService promotionRpcService;

    public MarketingMcpTools(MarketingAssetService marketingAssetService,
                             ObjectProvider<MediaWorkerRpcService> mediaWorkerRpcServiceProvider,
                             ObjectProvider<PromotionRpcService> promotionRpcServiceProvider) {
        this.marketingAssetService = marketingAssetService;
        this.mediaWorkerRpcService = mediaWorkerRpcServiceProvider.getIfAvailable();
        this.promotionRpcService = promotionRpcServiceProvider.getIfAvailable();
    }

    @org.springaicommunity.mcp.annotation.McpTool
    @Tool(description = "生成营销内容并分发到指定平台。为每个平台创建内容条目并提交发布。")
    public String publishMarketingContent(
            @ToolParam(description = "Listing ID") Long listingId,
            @ToolParam(description = "Comma-separated platforms, e.g. 'douyin,wechat,xiaohongshu'") String platforms,
            @ToolParam(description = "Marketing copywriting text") String copywriting) {
        List<String> platformList = parsePlatforms(platforms);
        List<String> assets = mediaWorkerRpcService == null ? List.of() : mediaWorkerRpcService.generateAssets(listingId, copywriting);
        List<MarketingContentDTO> contents = platformList.stream()
                .map(platform -> new MarketingContentDTO(listingId, platform, copywriting, assets, "DRAFT"))
                .toList();
        List<MarketingContentDTO> savedContents = marketingAssetService.saveContents(contents);
        if (promotionRpcService != null) {
            savedContents.forEach(promotionRpcService::publish);
        }
        return "已生成 " + savedContents.size() + " 条内容并提交发布。";
    }

    private List<String> parsePlatforms(String platforms) {
        if (platforms == null || platforms.isBlank()) {
            return List.of("douyin");
        }
        return Arrays.stream(platforms.split("[,，、\\s]+"))
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }
}
