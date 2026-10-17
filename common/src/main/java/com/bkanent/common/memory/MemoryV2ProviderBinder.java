package com.bkanent.common.memory;

import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.ServiceConfig;

/**
 * Managed memory v2 provider binding (KI-100): pure dubbo protocol via protocolIds and
 * the fixed group/version from MemoryV2Contract. Deployment configuration owns registry,
 * bind address and credentials; this binder registers no URL/host override.
 */
public final class MemoryV2ProviderBinder {

    private MemoryV2ProviderBinder() { }

    /** Builds the provider-side service registration for the v2 implementation. */
    public static ServiceConfig<MemoryV2RpcService> bind(MemoryV2RpcService implementation, ProviderConfig providerConfig) {
        if (implementation == null || providerConfig == null) {
            throw new IllegalStateException("memory v2 provider binding requires implementation and provider config");
        }
        ServiceConfig<MemoryV2RpcService> service = new ServiceConfig<>();
        service.setInterface(MemoryV2RpcService.class);
        service.setRef(implementation);
        service.setGroup(MemoryV2Contract.DUBBO_GROUP);
        service.setVersion(MemoryV2Contract.DUBBO_SERVICE_VERSION);
        service.setProtocolIds("dubbo");
        service.setProvider(providerConfig);
        return service;
    }
}
