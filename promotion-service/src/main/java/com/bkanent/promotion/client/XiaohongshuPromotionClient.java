package com.bkanent.promotion.client;

import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.promotion.model.PromotionPublishRequest;
import org.springframework.stereotype.Component;

/**
 * 小红书发布适配器。
 */
@Component
public class XiaohongshuPromotionClient extends AbstractPromotionPlatformClient {

    @Override
    public String platform() {
        return "XIAOHONGSHU";
    }

    @Override
    protected boolean doPublish(MarketingContentDTO content, PromotionPublishRequest request) {
        return true;
    }
}
