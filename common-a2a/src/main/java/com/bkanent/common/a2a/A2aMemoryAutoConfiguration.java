package com.bkanent.common.a2a;

import com.bkanent.common.memory.MemoryV2Contract;
import com.bkanent.common.memory.MemoryV2RpcService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bkanent.memory.runtime.MemoryWorkingSetCache;
import org.apache.dubbo.config.ReferenceConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Clock;
import java.util.Map;

/** Shared, lazy native Dubbo wiring for domain A2A memory hooks. */
@AutoConfiguration
@EnableConfigurationProperties(A2aMemoryProperties.class)
public class A2aMemoryAutoConfiguration {
    @Bean(destroyMethod = "destroy")
    public ReferenceConfig<MemoryV2RpcService> a2aMemoryV2Reference() {
        var reference = new ReferenceConfig<MemoryV2RpcService>();
        reference.setInterface(MemoryV2RpcService.class);
        reference.setGroup(MemoryV2Contract.DUBBO_GROUP);
        reference.setVersion(MemoryV2Contract.DUBBO_SERVICE_VERSION);
        reference.setProtocol("dubbo");
        reference.setCheck(false);
        reference.setInit(false);
        reference.setRetries(0);
        reference.setTimeout(5_000);
        reference.setParameters(Map.of("serialization", "fastjson2ctor"));
        return reference;
    }

    @Bean
    public MemoryWorkingSetCache a2aMemoryWorkingSetCache(A2aMemoryProperties properties) {
        properties.validate();
        return new MemoryWorkingSetCache(properties.getCacheEntries(), properties.getCacheBytes(),
                properties.getCacheTtlMs(), Clock.systemUTC());
    }

    @Bean(destroyMethod = "close")
    public A2aMemoryHookFactory a2aMemoryHookFactory(A2aMemoryProperties properties,
                                                     @Qualifier("a2aMemoryV2Reference") ReferenceConfig<MemoryV2RpcService> a2aMemoryV2Reference,
                                                     @Qualifier("a2aMemoryWorkingSetCache") MemoryWorkingSetCache a2aMemoryWorkingSetCache,
                                                     ObjectMapper objectMapper) {
        return new A2aMemoryHookFactory(properties, a2aMemoryV2Reference, a2aMemoryWorkingSetCache, objectMapper);
    }
}
