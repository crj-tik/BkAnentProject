package com.bkanent.agent.memory;

import com.bkanent.common.memory.*;
import com.bkanent.memory.runtime.MemoryWorkingSetCache;
import org.apache.dubbo.config.ReferenceConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties(SupervisorMemoryProperties.class)
public class SupervisorMemoryConfiguration {
    @Bean(destroyMethod = "destroy")
    public ReferenceConfig<MemoryV2RpcService> supervisorMemoryReference() {
        var ref = new ReferenceConfig<MemoryV2RpcService>();
        ref.setInterface(MemoryV2RpcService.class); ref.setGroup(MemoryV2Contract.DUBBO_GROUP); ref.setVersion(MemoryV2Contract.DUBBO_SERVICE_VERSION);
        ref.setProtocol("dubbo"); ref.setCheck(false); ref.setInit(false); ref.setRetries(0); ref.setTimeout(5000);
        ref.setParameters(java.util.Map.of("serialization", "fastjson2ctor"));
        return ref;
    }
    @Bean public MemoryWorkingSetCache supervisorMemoryCache(SupervisorMemoryProperties properties) {
        properties.validate();
        return new MemoryWorkingSetCache(properties.getCacheEntries(), properties.getCacheBytes(), properties.getCacheTtlMs(), Clock.systemUTC());
    }
}
