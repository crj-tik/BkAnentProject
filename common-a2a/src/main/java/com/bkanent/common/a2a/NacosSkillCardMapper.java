package com.bkanent.common.a2a;

import com.alibaba.cloud.ai.a2a.registry.nacos.utils.AgentCardConverterUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentExtension;
import java.util.List;

/** Starter 1.1.2.3 drops capabilities.extensions in both directions; keep the published contract intact. */
public final class NacosSkillCardMapper {
    private static final ObjectMapper MAPPER = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private NacosSkillCardMapper() {}

    public static com.alibaba.nacos.api.ai.model.a2a.AgentCard toNacos(AgentCard source) {
        var result = AgentCardConverterUtil.convertToNacosAgentCard(source);
        if (source.capabilities() != null) result.getCapabilities().setExtensions(
                source.capabilities().extensions() == null ? List.of() : source.capabilities().extensions().stream().map(extension -> {
                    var value = new com.alibaba.nacos.api.ai.model.a2a.AgentExtension();
                    value.setUri(extension.uri()); value.setDescription(extension.description());
                    value.setRequired(extension.required()); value.setParams(extension.params()); return value;
                }).toList());
        return result;
    }

    public static AgentCard toOfficial(com.alibaba.nacos.api.ai.model.a2a.AgentCard source) {
        var normalized = MAPPER.convertValue(source, com.alibaba.nacos.api.ai.model.a2a.AgentCard.class);
        if (normalized.getCapabilities() == null) normalized.setCapabilities(new com.alibaba.nacos.api.ai.model.a2a.AgentCapabilities());
        var capabilities = normalized.getCapabilities();
        if (capabilities.getStreaming() == null) capabilities.setStreaming(false);
        if (capabilities.getPushNotifications() == null) capabilities.setPushNotifications(false);
        if (capabilities.getStateTransitionHistory() == null) capabilities.setStateTransitionHistory(false);
        if (normalized.getSupportsAuthenticatedExtendedCard() == null) normalized.setSupportsAuthenticatedExtendedCard(false);
        var card = AgentCardConverterUtil.convertToA2aAgentCard(normalized);
        var preserved = new AgentCapabilities(card.capabilities().streaming(), card.capabilities().pushNotifications(),
                card.capabilities().stateTransitionHistory(), capabilities.getExtensions() == null ? List.of()
                : capabilities.getExtensions().stream().map(extension -> new AgentExtension(extension.getDescription(), extension.getParams(),
                        Boolean.TRUE.equals(extension.getRequired()), extension.getUri())).toList());
        return new AgentCard(card.name(), card.description(), card.url(), card.provider(), card.version(), card.documentationUrl(),
                preserved, card.defaultInputModes(), card.defaultOutputModes(), card.skills(), card.supportsAuthenticatedExtendedCard(),
                card.securitySchemes(), card.security(), card.iconUrl(), card.additionalInterfaces(), card.preferredTransport(), card.protocolVersion());
    }
}
