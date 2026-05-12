package com.bkanent.common.rpc;

import com.bkanent.common.model.MarketingContentDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 营销内容 RPC 接口。
 */
public interface MarketingContentRpcService {

    /**
     * 业务方法：saveGeneratedContents。
     */
    List<MarketingContentDTO> saveGeneratedContents(List<MarketingContentDTO> contents);

    void bindGeneratedAssets(Long contentId,
                             List<String> assetUrls,
                             String coverImageUrl,
                             String videoUrl,
                             String updateMessage);

    void updatePublishStatus(Long contentId,
                             String publishStatus,
                             String publishMessage,
                             String externalPublishId,
                             LocalDateTime publishTime);
}
