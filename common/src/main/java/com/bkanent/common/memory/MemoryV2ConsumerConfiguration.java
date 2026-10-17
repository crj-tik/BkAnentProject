package com.bkanent.common.memory;

import org.apache.dubbo.config.ReferenceConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Managed memory v2 consumer binding (KI-100): pure dubbo protocol selection with
 * the fixed group/version from MemoryV2Contract — the same consumer.protocol pinning
 * that fixed the Listing HTTP/REST mis-selection. Registry,
 * reachable addresses and credentials come only from deployment configuration; this
 * class exposes no URL/host selector, and a request can never choose its target.
 */
@Configuration
public class MemoryV2ConsumerConfiguration {

    @Bean
    @org.springframework.context.annotation.Profile("!local")
    public MemoryV2RpcService memoryV2RpcService(Environment environment) {
        return reference(environment).get();
    }

    /** Single construction point; assertions keep the binding pure-dubbo and version-pinned. */
    static ReferenceConfig<MemoryV2RpcService> reference(Environment environment) {
        if (environment.getProperty("memory.v2.dubbo.url") != null
                || environment.getProperty("memory.v2.dubbo.host") != null) {
            throw new IllegalStateException("memory v2 consumer must not be pointed at a URL or host");
        }
        ReferenceConfig<MemoryV2RpcService> reference = new ReferenceConfig<>();
        reference.setInterface(MemoryV2RpcService.class);
        reference.setGroup(MemoryV2Contract.DUBBO_GROUP);
        reference.setVersion(MemoryV2Contract.DUBBO_SERVICE_VERSION);
        reference.setProtocol("dubbo");
        reference.setCheck(false);
        reference.setRetries(0);
        reference.setTimeout(5000);
        return reference;
    }
}
