package com.bkanent.promotion.rpc;

import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.common.rpc.PromotionRpcService;
import com.bkanent.promotion.client.PromotionPlatformClientDispatcher;
import com.bkanent.promotion.model.PromotionPlatformPublishResult;
import com.bkanent.promotion.model.PromotionPublishRequest;
import org.apache.dubbo.config.annotation.DubboService;

import java.math.BigDecimal;

/**
 * 宣传发布 RPC 服务实现。
 */
@DubboService
public class PromotionRpcServiceImpl implements PromotionRpcService {

    private final PromotionPlatformClientDispatcher platformClientDispatcher;

    public PromotionRpcServiceImpl(PromotionPlatformClientDispatcher platformClientDispatcher) {
        this.platformClientDispatcher = platformClientDispatcher;
    }

    @Override
    public String publish(MarketingContentDTO content) {
        PromotionPlatformPublishResult result = platformClientDispatcher.publish(content, new PromotionPublishRequest(
                content.id(),
                content.listingId(),
                content.platform(),
                content.platform().toLowerCase() + "_official",
                "RPC",
                BigDecimal.ZERO
        ));
        return result.success() ? "PUBLISHED:" + content.platform() : "FAILED:" + content.platform();
    }
}
