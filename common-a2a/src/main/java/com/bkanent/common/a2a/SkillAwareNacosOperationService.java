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
    private final String namespace;
    private final java.util.function.Supplier<com.alibaba.nacos.maintainer.client.ai.AiMaintainerService> maintainer;
    public SkillAwareNacosOperationService(A2aService service, NacosA2aProperties nacos,
                                            A2aServerProperties server, NacosA2aRegistryProperties registry) {
        this(service, nacos, server, registry, memoizedMaintainer(nacos));
    }
    public SkillAwareNacosOperationService(A2aService service, NacosA2aProperties nacos,
                                            A2aServerProperties server, NacosA2aRegistryProperties registry,
                                            java.util.function.Supplier<com.alibaba.nacos.maintainer.client.ai.AiMaintainerService> maintainer) {
        super(service, nacos, server, registry);
        this.service = service; this.server = server; this.registry = registry;
        this.maintainer = maintainer;
        this.namespace = org.springframework.util.StringUtils.hasText(nacos.getNamespace()) ? nacos.getNamespace() : "public";
    }
    private static java.util.function.Supplier<com.alibaba.nacos.maintainer.client.ai.AiMaintainerService> memoizedMaintainer(NacosA2aProperties properties) {
        return new java.util.function.Supplier<>() {
            private com.alibaba.nacos.maintainer.client.ai.AiMaintainerService client;
            @Override public synchronized com.alibaba.nacos.maintainer.client.ai.AiMaintainerService get() {
                if (client == null) {
                    try { client = com.alibaba.nacos.maintainer.client.ai.AiMaintainerFactory.createAiMaintainerService(properties.getNacosProperties()); }
                    catch (NacosException exception) { throw new NacosRuntimeException(exception.getErrCode(), exception.getErrMsg()); }
                }
                return client;
            }
        };
    }
    /** Nacos 3.1 release is create-only for an existing version; updates use the official update API. */
    public void republishAgent(AgentCard card) {
        registerAgent(card);
        try {
            var existing = NacosSkillCardMapper.toOfficial(service.getAgentCard(card.name(), card.version(), "URL"));
            if (java.util.Objects.equals(existing.skills(), card.skills())
                    && java.util.Objects.equals(existing.capabilities(), card.capabilities())) return;
            if (!maintainer.get().updateAgentCard(NacosSkillCardMapper.toNacos(card), namespace, registry.isRegisterAsLatest(), "SERVICE"))
                throw new IllegalStateException("NACOS_CARD_UPDATE_REJECTED");
        } catch (NacosException exception) { throw new NacosRuntimeException(exception.getErrCode(), exception.getErrMsg()); }
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
