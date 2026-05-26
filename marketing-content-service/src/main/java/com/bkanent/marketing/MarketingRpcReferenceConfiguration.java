package com.bkanent.marketing;

import com.bkanent.common.rpc.MediaWorkerRpcService;
import com.bkanent.common.rpc.PromotionRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!local")
public class MarketingRpcReferenceConfiguration {

    @DubboReference(check = false)
    private MediaWorkerRpcService mediaWorkerRpcService;

    @DubboReference(check = false)
    private PromotionRpcService promotionRpcService;

    @Bean
    @Primary
    public MediaWorkerRpcService mediaWorkerRpcServiceBridge() {
        return mediaWorkerRpcService;
    }

    @Bean
    @Primary
    public PromotionRpcService promotionRpcServiceBridge() {
        return promotionRpcService;
    }
}
