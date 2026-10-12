package com.bkanent.agent;

import com.bkanent.common.rpc.*;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!local")
public class AgentRagRpcReferenceConfiguration {
    @DubboReference(check = false, timeout = 20000, retries = 0)
    private RagRetrievalRpcService retrieval;
    @DubboReference(check = false, timeout = 20000, retries = 0)
    private RagSpaceAdminRpcService admin;
    @DubboReference(check = false, timeout = 20000, retries = 0)
    private RagListingDomainRpcService listing;
    @DubboReference(check = false, timeout = 20000, retries = 0)
    private MemoryRagRpcService memory;
    @Bean public RagRetrievalRpcService ragRetrievalBridge() { return retrieval; }
    @Bean public RagSpaceAdminRpcService ragAdminBridge() { return admin; }
    @Bean public RagListingDomainRpcService ragListingDomainBridge() { return listing; }
    @Bean public MemoryRagRpcService memoryRagBridge() { return memory; }
}
