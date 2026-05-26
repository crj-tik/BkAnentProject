package com.bkanent.agent.registry;

import com.alibaba.cloud.ai.graph.agent.a2a.AgentCardProvider;
import com.alibaba.cloud.ai.graph.agent.a2a.AgentCardWrapper;
import com.alibaba.cloud.ai.graph.agent.a2a.RemoteAgentCardProvider;
import com.bkanent.common.agent.AgentCard;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Component
public class OfficialAgentCardDiscoveryClient {

    public Optional<AgentCard> fetchAgentCard(String baseUrl, String cardPath) {
        try {
            AgentCardProvider provider = RemoteAgentCardProvider.newProvider(baseUrl + cardPath);
            AgentCardWrapper wrapper = provider.getAgentCard();
            if (wrapper == null) {
                return Optional.empty();
            }
            return Optional.of(new AgentCard(
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
            ));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }
}
