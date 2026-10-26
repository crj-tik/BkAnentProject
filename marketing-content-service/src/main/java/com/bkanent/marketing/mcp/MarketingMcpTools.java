package com.bkanent.marketing.mcp;

import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.common.rpc.MediaWorkerRpcService;
import com.bkanent.common.rpc.PromotionRpcService;
import com.bkanent.common.tool.McpTool;
import com.bkanent.common.a2a.VerifiedDomainAuthorization;
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

    @Tool(description = "生成营销内容并分发到指定平台。为每个平台创建内容条目并提交发布。")
    public String publishMarketingContent(
            @ToolParam(description = "Listing ID") Long listingId,
            @ToolParam(description = "Comma-separated platforms, e.g. 'douyin,wechat,xiaohongshu'") String platforms,
            @ToolParam(description = "Marketing copywriting text") String copywriting) {
        VerifiedDomainAuthorization.requireBrokerScope(
                "marketing.publish.execute.own", "marketing.publish.execute.all");
        throw new UnsupportedOperationException("真实营销发布渠道尚未接通，当前不执行媒体生成、保存或模拟发布");
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
