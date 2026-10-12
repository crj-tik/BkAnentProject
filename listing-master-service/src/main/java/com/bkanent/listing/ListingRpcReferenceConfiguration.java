package com.bkanent.listing;

import com.bkanent.common.rpc.RagIndexRpcService;
import com.bkanent.common.rpc.RagPrincipalRpcService;
import com.bkanent.common.rpc.RagRetrievalRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableScheduling
public class ListingRpcReferenceConfiguration {
    @Bean
    public TransactionTemplate listingRagTransactionTemplate(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }
    @Configuration
    @Profile("!local")
    static class Distributed {
        @DubboReference(check = false, timeout = 20000, retries = 0)
        private RagRetrievalRpcService retrieval;
        @DubboReference(check = false, timeout = 5000, retries = 0)
        private RagIndexRpcService index;
        @DubboReference(check = false, timeout = 5000, retries = 0)
        private RagPrincipalRpcService principal;
        @Bean public RagRetrievalRpcService ragRetrievalRpcService() { return retrieval; }
        @Bean public RagIndexRpcService ragIndexRpcService() { return index; }
        @Bean public RagPrincipalRpcService ragPrincipalRpcService() { return principal; }
    }
}
