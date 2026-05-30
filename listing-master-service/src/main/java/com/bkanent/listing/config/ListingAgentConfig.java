package com.bkanent.listing.config;

import com.bkanent.listing.tool.ListingTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ListingAgentProperties.class)
public class ListingAgentConfig {

    @Bean("listingToolCallbackProvider")
    public ToolCallbackProvider listingToolCallbackProvider(ListingTools listingTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(listingTools)
                .build();
    }

    @Bean("listingChatClient")
    public ChatClient listingChatClient(ChatModel chatModel,
                                        ToolCallbackProvider listingToolCallbackProvider) {
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(listingToolCallbackProvider)
                .build();
    }
}
