package com.bkanent.agent.registry;

import com.alibaba.cloud.ai.a2a.registry.nacos.discovery.NacosAgentCardProvider;
import com.alibaba.cloud.ai.graph.agent.a2a.AgentCardProvider;
import com.alibaba.cloud.ai.graph.agent.a2a.AgentCardWrapper;
import com.alibaba.cloud.ai.graph.agent.a2a.RemoteAgentCardProvider;
import com.bkanent.common.agent.AgentCard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Component
public class OfficialAgentCardDiscoveryClient implements AgentCardDiscoveryClient {

    private final ObjectProvider<NacosAgentCardProvider> nacosAgentCardProvider;

    public OfficialAgentCardDiscoveryClient(ObjectProvider<NacosAgentCardProvider> nacosAgentCardProvider) {
        this.nacosAgentCardProvider = nacosAgentCardProvider;
    }

    @Override
    public Optional<AgentCard> fetchByAgentName(String agentName) {
        if (!StringUtils.hasText(agentName)) {
            return Optional.empty();
        }
        NacosAgentCardProvider provider = nacosAgentCardProvider.getIfAvailable();
        if (provider == null || !provider.supportGetAgentCardByName()) {
            return Optional.empty();
        }
        try {
            AgentCardWrapper wrapper = provider.getAgentCard(agentName);
            if (wrapper == null) {
                return Optional.empty();
            }
            return Optional.of(convertWrapper(wrapper));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<AgentCard> fetchAgentCard(String baseUrl, String cardPath) {
        if (!StringUtils.hasText(baseUrl)) {
            return Optional.empty();
        }
        try {
            AgentCardProvider provider = RemoteAgentCardProvider.newProvider(baseUrl + cardPath);
            AgentCardWrapper wrapper = provider.getAgentCard();
            if (wrapper == null) {
                return Optional.empty();
            }
            return Optional.of(convertWrapper(wrapper));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private AgentCard convertWrapper(AgentCardWrapper wrapper) {
        return new AgentCard(
                null,
                wrapper.name(),
                wrapper.description(),
                wrapper.version(),
                List.of(),
                List.of(),
                Boolean.valueOf(StringUtils.hasText(wrapper.preferredTransport())),
                Boolean.FALSE,
                wrapper.url(),
                wrapper.defaultInputModes() == null ? List.of() : List.copyOf(wrapper.defaultInputModes()),
                wrapper.defaultOutputModes() == null ? List.of() : List.copyOf(wrapper.defaultOutputModes())
        );
    }
}
