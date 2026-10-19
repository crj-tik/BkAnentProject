package com.bkanent.memory.config;

import com.bkanent.common.rpc.RagIndexRpcService;
import com.bkanent.common.rpc.RagPrincipalRpcService;
import com.bkanent.common.rpc.RagRetrievalRpcService;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.bkanent.memory.config.MemoryV2SecurityProperties;

@Configuration
@EnableScheduling
@EnableDubbo(scanBasePackages = "com.bkanent.memory.rpc")
@EnableConfigurationProperties(MemoryV2SecurityProperties.class)
public class MemoryRagRpcConfiguration {
    @Bean(destroyMethod = "destroy")
    public ReferenceConfig<AuthPermissionRpcService> memoryAuthPermissionReference() { return reference(AuthPermissionRpcService.class); }
    @Bean(destroyMethod = "destroy")
    public ReferenceConfig<RagPrincipalRpcService> memoryPrincipalReference() { return reference(RagPrincipalRpcService.class); }
    @Bean(destroyMethod = "destroy")
    public ReferenceConfig<RagRetrievalRpcService> memoryRetrievalReference() { return reference(RagRetrievalRpcService.class); }
    @Bean(destroyMethod = "destroy")
    public ReferenceConfig<RagIndexRpcService> memoryIndexReference() { return reference(RagIndexRpcService.class); }
    @Bean
    public AuthPermissionRpcService memoryAuthPermissions(ReferenceConfig<AuthPermissionRpcService> memoryAuthPermissionReference) {
        return new AuthPermissionRpcService() {
            public boolean validateToken(String token) { return memoryAuthPermissionReference.get().validateToken(token); }
            public com.bkanent.common.rpc.AuthenticatedPrincipal resolvePrincipal(String token) { return memoryAuthPermissionReference.get().resolvePrincipal(token); }
            public boolean hasPermission(Long userId, String permissionCode) { return memoryAuthPermissionReference.get().hasPermission(userId, permissionCode); }
        };
    }
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
        // fastjson2 2.0.52..2.0.59 JSONB cannot decode FieldBased record payloads with list fields
        // through Dubbo's security handler (TYPED_ANY startArray crash, KI-113 follow-up). The
        // RAG provider prefers "fastjson2,hessian2" and therefore accepts an explicit hessian2
        // consumer; the wire choice does not weaken any signed envelope in the payloads.
        reference.setParameters(java.util.Map.of("serialization", "hessian2"));
        return reference;
    }
}
