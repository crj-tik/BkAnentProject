package com.bkanent.promotion.service;

import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.common.rpc.PromotionRpcService;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
public class PromotionRpcServiceImpl implements PromotionRpcService {

    @Override
    public String publish(MarketingContentDTO content) {
        return "PUBLISHED:" + content.platform();
    }
}
