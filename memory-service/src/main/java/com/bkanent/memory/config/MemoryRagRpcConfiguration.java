package com.bkanent.memory.config;

import com.bkanent.common.rpc.RagIndexRpcService;
import com.bkanent.common.rpc.RagPrincipalRpcService;
import com.bkanent.common.rpc.RagRetrievalRpcService;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableDubbo(scanBasePackages = "com.bkanent.memory.rpc")
public class MemoryRagRpcConfiguration {
    @Bean(destroyMethod = "destroy")
    public ReferenceConfig<RagPrincipalRpcService> memoryPrincipalReference() { return reference(RagPrincipalRpcService.class); }
    @Bean(destroyMethod = "destroy")
    public ReferenceConfig<RagRetrievalRpcService> memoryRetrievalReference() { return reference(RagRetrievalRpcService.class); }
    @Bean(destroyMethod = "destroy")
    public ReferenceConfig<RagIndexRpcService> memoryIndexReference() { return reference(RagIndexRpcService.class); }
    @Bean
    public RagPrincipalRpcService memoryPrincipals(ReferenceConfig<RagPrincipalRpcService> memoryPrincipalReference) {
        return (credentials, authentication) -> memoryPrincipalReference.get().resolveRagPrincipal(credentials, authentication);
    }
    @Bean
    public RagRetrievalRpcService memoryRetrieval(ReferenceConfig<RagRetrievalRpcService> memoryRetrievalReference) {
        return (request, authentication, credentials, scope) -> memoryRetrievalReference.get().search(request, authentication, credentials, scope);
    }
    @Bean
    public RagIndexRpcService memoryIndex(ReferenceConfig<RagIndexRpcService> memoryIndexReference) {
        return new RagIndexRpcService() {
            public com.bkanent.common.rag.RagIndexStatus submit(com.bkanent.common.rag.RagIndexEvent event, com.bkanent.common.rag.RagAuthEnvelope authentication) { return memoryIndexReference.get().submit(event, authentication); }
            public com.bkanent.common.rag.RagIndexStatus getStatus(com.bkanent.common.rag.RagStatusRequest request, com.bkanent.common.rag.RagAuthEnvelope authentication) { return memoryIndexReference.get().getStatus(request, authentication); }
        };
    }
    private static <T> ReferenceConfig<T> reference(Class<T> type) {
        ReferenceConfig<T> reference = new ReferenceConfig<>();
        reference.setInterface(type);
        reference.setCheck(false);
        reference.setInit(false);
        reference.setTimeout(20000);
        reference.setRetries(0);
        return reference;
    }
}
