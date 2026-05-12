package com.bkanent.agent;

import com.bkanent.common.rpc.ListingMasterRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!local")
public class AgentRpcReferenceConfiguration {

    @DubboReference(check = false)
    private ListingMasterRpcService listingMasterRpcService;

    @Bean
    public ListingMasterRpcService listingMasterRpcServiceBridge() {
        return listingMasterRpcService;
    }
}
