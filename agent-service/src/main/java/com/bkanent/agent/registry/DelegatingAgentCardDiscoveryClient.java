package com.bkanent.agent.registry;

import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.common.agent.AgentCard;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

@Primary
@Component
public class DelegatingAgentCardDiscoveryClient implements AgentCardDiscoveryClient {

    private final DistributedAgentProperties properties;
    private final HttpAgentCardDiscoveryClient httpAgentCardDiscoveryClient;
    private final OfficialAgentCardDiscoveryClient officialAgentCardDiscoveryClient;

    public DelegatingAgentCardDiscoveryClient(DistributedAgentProperties properties,
                                              HttpAgentCardDiscoveryClient httpAgentCardDiscoveryClient,
                                              OfficialAgentCardDiscoveryClient officialAgentCardDiscoveryClient) {
        this.properties = properties;
        this.httpAgentCardDiscoveryClient = httpAgentCardDiscoveryClient;
        this.officialAgentCardDiscoveryClient = officialAgentCardDiscoveryClient;
    }

    @Override
    public Optional<AgentCard> fetchByAgentName(String agentName) {
        if (!StringUtils.hasText(agentName)) {
            return Optional.empty();
        }
        String provider = Optional.ofNullable(properties.getA2a().getDiscoveryProvider())
                .orElse("custom")
                .toLowerCase(Locale.ROOT);
        if ("official".equals(provider)) {
            Optional<AgentCard> officialCard = officialAgentCardDiscoveryClient.fetchByAgentName(agentName);
            if (officialCard.isPresent()) {
                return officialCard;
            }
        }
        return AgentCardDiscoveryClient.super.fetchByAgentName(agentName);
    }

    @Override
    public Optional<AgentCard> fetchAgentCard(String baseUrl, String cardPath) {
        if (!StringUtils.hasText(baseUrl)) {
            return Optional.empty();
        }
        String provider = Optional.ofNullable(properties.getA2a().getDiscoveryProvider())
                .orElse("custom")
                .toLowerCase(Locale.ROOT);
        if ("official".equals(provider)) {
            Optional<AgentCard> officialCard = officialAgentCardDiscoveryClient.fetchAgentCard(baseUrl, cardPath);
            if (officialCard.isPresent()) {
                return officialCard;
            }
        }
        return httpAgentCardDiscoveryClient.fetchAgentCard(baseUrl, cardPath);
    }
}
