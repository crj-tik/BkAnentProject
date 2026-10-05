package com.bkanent.common.a2a;

import com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties;
import com.alibaba.cloud.ai.a2a.registry.nacos.properties.NacosA2aProperties;
import com.alibaba.cloud.ai.a2a.registry.nacos.register.NacosA2aRegistryProperties;
import com.alibaba.cloud.ai.a2a.registry.nacos.service.NacosA2aOperationService;
import com.alibaba.nacos.api.ai.A2aService;
import com.alibaba.nacos.api.ai.model.a2a.AgentEndpoint;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.exception.runtime.NacosRuntimeException;
import io.a2a.spec.AgentCard;

/** Same SDK registration and latest-version policy as the Starter, with a lossless Card mapper. */
public final class SkillAwareNacosOperationService extends NacosA2aOperationService {
    private final A2aService service;
    private final A2aServerProperties server;
    private final NacosA2aRegistryProperties registry;
    public SkillAwareNacosOperationService(A2aService service, NacosA2aProperties nacos,
                                            A2aServerProperties server, NacosA2aRegistryProperties registry) {
        super(service, nacos, server, registry);
        this.service = service; this.server = server; this.registry = registry;
    }
    @Override
    public void registerAgent(AgentCard card) {
        var published = NacosSkillCardMapper.toNacos(card);
        var endpoint = new AgentEndpoint(); endpoint.setVersion(card.version()); endpoint.setPath(server.getMessageUrl());
        endpoint.setTransport(card.preferredTransport()); endpoint.setAddress(server.getAddress()); endpoint.setPort(server.getPort());
        try {
            service.releaseAgentCard(published, "SERVICE", registry.isRegisterAsLatest());
            service.registerAgentEndpoint(card.name(), endpoint);
        } catch (NacosException exception) { throw new NacosRuntimeException(exception.getErrCode(), exception.getErrMsg()); }
    }
}
