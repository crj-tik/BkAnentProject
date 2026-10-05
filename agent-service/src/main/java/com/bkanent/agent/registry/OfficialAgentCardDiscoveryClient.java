package com.bkanent.agent.registry;

import com.alibaba.cloud.ai.a2a.registry.nacos.discovery.NacosAgentCardProvider;
import com.alibaba.cloud.ai.graph.agent.a2a.AgentCardProvider;
import com.alibaba.cloud.ai.graph.agent.a2a.AgentCardWrapper;
import com.alibaba.cloud.ai.graph.agent.a2a.RemoteAgentCardProvider;
import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentSkillDescriptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
public class OfficialAgentCardDiscoveryClient implements AgentCardDiscoveryClient {

    private final ObjectProvider<NacosAgentCardProvider> nacosAgentCardProvider;
    private ObjectProvider<com.alibaba.nacos.api.ai.A2aService> rawNacosService;

    @org.springframework.beans.factory.annotation.Autowired
    public void setRawNacosService(ObjectProvider<com.alibaba.nacos.api.ai.A2aService> service) { this.rawNacosService = service; }

    public OfficialAgentCardDiscoveryClient(ObjectProvider<NacosAgentCardProvider> nacosAgentCardProvider) {
        this.nacosAgentCardProvider = nacosAgentCardProvider;
    }

    @Override
    public Optional<AgentCard> fetchByAgentName(String agentName) {
        if (!StringUtils.hasText(agentName)) {
            return Optional.empty();
        }
        var rawService = rawNacosService == null ? null : rawNacosService.getIfAvailable();
        if (rawService != null) {
            try {
                var published = rawService.getAgentCard(agentName);
                if (published == null) return Optional.empty();
                var card = convertWrapper(new com.alibaba.cloud.ai.a2a.registry.nacos.discovery.NacosAgentCardWrapper(
                        com.bkanent.common.a2a.NacosSkillCardMapper.toOfficial(published)));
                // SDK conversion requires primitive defaults; restore raw optional facts at our boundary.
                var raw = published.getCapabilities();
                Map<String, Object> facts = new LinkedHashMap<>(card.capabilities());
                optionalFact(facts, "streaming", raw == null ? null : raw.getStreaming());
                optionalFact(facts, "pushNotifications", raw == null ? null : raw.getPushNotifications());
                optionalFact(facts, "stateTransitionHistory", raw == null ? null : raw.getStateTransitionHistory());
                card = new AgentCard(card.agentId(), card.name(), card.description(), card.version(), card.supportedSkills(),
                        card.supportedDomains(), raw == null ? null : raw.getStreaming(), card.supportsAsyncTask(),
                        card.a2aEndpoint(), card.inputModes(), card.outputModes(), card.skillDescriptors(), facts,
                        card.preferredTransport(), card.protocolVersion());
                return StringUtils.hasText(card.a2aEndpoint()) ? Optional.of(card) : Optional.empty();
            } catch (Exception exception) { return Optional.empty(); }
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
            AgentCard card = convertWrapper(wrapper);
            return StringUtils.hasText(card.a2aEndpoint()) ? Optional.of(card) : Optional.empty();
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<AgentCard> fetchAgentCard(String baseUrl, String cardPath) {
        if (!StringUtils.hasText(baseUrl)
                || !StringUtils.hasText(cardPath)
                || !cardPath.contains("/.well-known/agent.json")) {
            return Optional.empty();
        }
        try {
            AgentCardProvider provider = RemoteAgentCardProvider.newProvider(baseUrl + cardPath);
            AgentCardWrapper wrapper = provider.getAgentCard();
            if (wrapper == null) {
                return Optional.empty();
            }
            AgentCard card = convertWrapper(wrapper);
            return StringUtils.hasText(card.a2aEndpoint()) ? Optional.of(card) : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private void optionalFact(Map<String, Object> facts, String name, Boolean value) {
        if (value == null) facts.remove(name); else facts.put(name, value);
    }

    AgentCard convertWrapper(AgentCardWrapper wrapper) {
        List<AgentSkillDescriptor> skills = wrapper.skills() == null ? List.of() : wrapper.skills().stream()
                .filter(Objects::nonNull)
                .map(skill -> new AgentSkillDescriptor(skill.id(), skill.name(), skill.description(), skill.tags(),
                        skill.examples(), skill.inputModes(), skill.outputModes()))
                .toList();
        Map<String, Object> capabilities = new LinkedHashMap<>();
        if (wrapper.capabilities() != null) {
            capabilities.put("streaming", wrapper.capabilities().streaming());
            capabilities.put("pushNotifications", wrapper.capabilities().pushNotifications());
            capabilities.put("stateTransitionHistory", wrapper.capabilities().stateTransitionHistory());
            capabilities.put("extensions", wrapper.capabilities().extensions() == null ? List.of()
                    : wrapper.capabilities().extensions().stream().filter(Objects::nonNull).map(extension -> {
                        Map<String, Object> facts = new LinkedHashMap<>();
                        if (extension.uri() != null) facts.put("uri", extension.uri());
                        if (extension.description() != null) facts.put("description", extension.description());
                        facts.put("required", extension.required());
                        facts.put("params", extension.params() == null ? Map.of() : Map.copyOf(extension.params()));
                        return Map.copyOf(facts);
                    }).toList());
        }
        return new AgentCard(
                null,
                wrapper.name(),
                wrapper.description(),
                wrapper.version(),
                skills.stream().map(AgentSkillDescriptor::id).filter(StringUtils::hasText).toList(),
                List.of(),
                wrapper.capabilities() == null ? null : wrapper.capabilities().streaming(),
                null,
                wrapper.url(),
                wrapper.defaultInputModes() == null ? List.of() : List.copyOf(wrapper.defaultInputModes()),
                wrapper.defaultOutputModes() == null ? List.of() : List.copyOf(wrapper.defaultOutputModes()),
                skills,
                capabilities,
                wrapper.preferredTransport(),
                wrapper.protocolVersion()
        );
    }
}
