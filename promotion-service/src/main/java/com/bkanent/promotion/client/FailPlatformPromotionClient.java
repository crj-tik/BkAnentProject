package com.bkanent.promotion.client;

import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.promotion.model.PromotionPublishRequest;
import org.springframework.stereotype.Component;

/**
 * 失败场景演示适配器。
 */
@Component
public class FailPlatformPromotionClient extends AbstractPromotionPlatformClient {

    @Override
    public String platform() {
        return "FAIL_PLATFORM";
    }

    @Override
    protected boolean doPublish(MarketingContentDTO content, PromotionPublishRequest request) {
        return false;
    }
}
