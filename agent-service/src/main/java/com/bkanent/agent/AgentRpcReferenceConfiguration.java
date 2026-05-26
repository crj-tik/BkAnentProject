package com.bkanent.agent;

import com.bkanent.common.rpc.ListingMasterRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * AgentRpcReferenceConfiguration 配置类。
 */
@Configuration
@Profile("!local")
public class AgentRpcReferenceConfiguration {

    /**
     * 字段：listingMasterRpcService。
     */
    @DubboReference(check = false)
    private ListingMasterRpcService listingMasterRpcService;

    /**
     * 查询ingMasterRpcServiceBridge。
     */
    @Bean
    public ListingMasterRpcService listingMasterRpcServiceBridge() {
        return listingMasterRpcService;
    }
}
