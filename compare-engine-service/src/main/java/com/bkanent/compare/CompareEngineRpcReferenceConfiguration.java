package com.bkanent.compare;

import com.bkanent.common.rpc.ListingMasterRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!local")
public class CompareEngineRpcReferenceConfiguration {

    @DubboReference(check = false)
    private ListingMasterRpcService listingMasterRpcService;

    @Bean
    @Primary
    public ListingMasterRpcService listingMasterRpcServiceBridge() {
        return listingMasterRpcService;
    }
}
