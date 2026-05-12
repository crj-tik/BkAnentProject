package com.bkanent.marketing.rpc;

import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.common.rpc.MarketingContentRpcService;
import com.bkanent.marketing.model.MarketingPublishStatusUpdateRequest;
import com.bkanent.marketing.service.MarketingAssetService;
import org.apache.dubbo.config.annotation.DubboService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 营销内容 RPC 服务实现。
 */
@DubboService
public class MarketingContentRpcServiceImpl implements MarketingContentRpcService {

    private final MarketingAssetService marketingAssetService;

    public MarketingContentRpcServiceImpl(MarketingAssetService marketingAssetService) {
        this.marketingAssetService = marketingAssetService;
    }

    @Override
    public List<MarketingContentDTO> saveGeneratedContents(List<MarketingContentDTO> contents) {
        return marketingAssetService.saveContents(contents);
    }

    @Override
    public void bindGeneratedAssets(Long contentId,
                                    List<String> assetUrls,
                                    String coverImageUrl,
                                    String videoUrl,
                                    String updateMessage) {
        marketingAssetService.bindGeneratedAssets(contentId, assetUrls, coverImageUrl, videoUrl, updateMessage);
    }

    @Override
    public void updatePublishStatus(Long contentId,
                                    String publishStatus,
                                    String publishMessage,
                                    String externalPublishId,
                                    LocalDateTime publishTime) {
        marketingAssetService.updatePublishStatus(contentId,
                new MarketingPublishStatusUpdateRequest(publishStatus, publishMessage, externalPublishId, publishTime));
    }
}
