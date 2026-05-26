package com.bkanent.listing;

import com.bkanent.common.rpc.AgentRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * ListingRpcReferenceConfiguration 配置类。
 */
@Configuration
@Profile("!local")
public class ListingRpcReferenceConfiguration {

    @DubboReference(check = false)
    private AgentRpcService agentRpcService;

    @Bean
    public AgentRpcService agentRpcServiceBridge() {
        return agentRpcService;
    }
}
