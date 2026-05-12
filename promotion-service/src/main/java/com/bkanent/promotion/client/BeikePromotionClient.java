package com.bkanent.promotion.client;

import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.promotion.model.PromotionPublishRequest;
import org.springframework.stereotype.Component;

/**
 * 贝壳发布适配器。
 */
@Component
public class BeikePromotionClient extends AbstractPromotionPlatformClient {

    @Override
    public String platform() {
        return "BEIKE";
    }

    @Override
    protected boolean doPublish(MarketingContentDTO content, PromotionPublishRequest request) {
        return true;
    }
}
