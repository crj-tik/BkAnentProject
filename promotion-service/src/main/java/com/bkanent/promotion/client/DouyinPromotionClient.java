package com.bkanent.promotion.client;

import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.promotion.model.PromotionPublishRequest;
import org.springframework.stereotype.Component;

/**
 * 抖音发布适配器。
 */
@Component
public class DouyinPromotionClient extends AbstractPromotionPlatformClient {

    @Override
    public String platform() {
        return "DOUYIN";
    }

    @Override
    protected boolean doPublish(MarketingContentDTO content, PromotionPublishRequest request) {
        return true;
    }
}
