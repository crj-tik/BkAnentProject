package com.bkanent.promotion.client;

import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.promotion.model.PromotionPublishRequest;
import com.bkanent.promotion.model.PromotionPlatformPublishResult;

/**
 * 第三方平台发布适配器接口。
 */
public interface PromotionPlatformClient {

    /**
     * 返回当前适配的平台编码。
     */
    /**
     * 业务方法：platform。
     */
    String platform();

    /**
     * 执行平台发布。
     */
    /**
     * 业务方法：publish。
     */
    PromotionPlatformPublishResult publish(MarketingContentDTO content, PromotionPublishRequest request);
}
