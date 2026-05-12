package com.bkanent.promotion.client;

import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.promotion.model.PromotionPlatformPublishResult;
import com.bkanent.promotion.model.PromotionPublishRequest;

import java.time.LocalDateTime;

/**
 * 第三方平台发布适配器基类。
 */
public abstract class AbstractPromotionPlatformClient implements PromotionPlatformClient {

    @Override
    public PromotionPlatformPublishResult publish(MarketingContentDTO content, PromotionPublishRequest request) {
        boolean success = doPublish(content, request);
        String platformCode = platform().toUpperCase();
        LocalDateTime publishTime = LocalDateTime.now();
        String externalPublishId = "PUB-" + platformCode + "-" + System.currentTimeMillis();
        String publishMessage = success
                ? buildSuccessMessage(request)
                : buildFailureMessage(request);
        return new PromotionPlatformPublishResult(
                success,
                success ? "SUCCESS" : "FAILED",
                externalPublishId,
                publishMessage,
                publishTime
        );
    }

    protected abstract boolean doPublish(MarketingContentDTO content, PromotionPublishRequest request);

    protected String buildSuccessMessage(PromotionPublishRequest request) {
        return "发布成功，平台=" + request.platform();
    }

    protected String buildFailureMessage(PromotionPublishRequest request) {
        return "发布失败，平台返回模拟错误";
    }
}
