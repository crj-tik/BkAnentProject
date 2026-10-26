package com.bkanent.promotion.client;

import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.promotion.config.PromotionIntegrationProperties;
import com.bkanent.promotion.model.PromotionPlatformPublishResult;
import com.bkanent.promotion.model.PromotionPublishRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 平台适配器分发器。
 */
@Component
public class PromotionPlatformClientDispatcher {

    public PromotionPlatformClientDispatcher(List<PromotionPlatformClient> clients,
                                              PromotionIntegrationProperties integrationProperties) {
    }

    public PromotionPlatformPublishResult publish(MarketingContentDTO content, PromotionPublishRequest request) {
        throw new UnsupportedOperationException("real promotion platform providers are not configured; mock adapters cannot publish");
    }
}
