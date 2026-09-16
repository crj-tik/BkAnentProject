package com.bkanent.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import java.util.Arrays;

@Configuration
@EnableConfigurationProperties({
        MilvusConnectionProperties.class,
        AgentMilvusProperties.class,
        AgentChatProperties.class,
        ListingRagProperties.class,
        DistributedAgentProperties.class,
        MemoryServiceProperties.class,
        SystemConstraintBootstrapProperties.class
})
/**
 * AgentServiceConfiguration 配置类。
 */
public class AgentServiceConfiguration {

    @Bean("combinedToolCallbackProvider")
    public ToolCallbackProvider combinedToolCallbackProvider(
            @Qualifier("localToolCallbackProvider") ToolCallbackProvider localToolCallbackProvider,
            @Qualifier("mcpToolCallbacks") ToolCallbackProvider mcpToolCallbacks) {
        return () -> {
            org.springframework.ai.tool.ToolCallback[] localCallbacks = localToolCallbackProvider.getToolCallbacks();
            org.springframework.ai.tool.ToolCallback[] mcpCallbacks = mcpToolCallbacks.getToolCallbacks();
            org.springframework.ai.tool.ToolCallback[] combined = Arrays.copyOf(localCallbacks, localCallbacks.length + mcpCallbacks.length);
            System.arraycopy(mcpCallbacks, 0, combined, localCallbacks.length, mcpCallbacks.length);
            return combined;
        };
    }

    /**
     * 处理对话client。
     */
    @Bean("chatClient")
    public ChatClient chatClient(ChatModel chatModel,
                                 @Qualifier("combinedToolCallbackProvider") ToolCallbackProvider combinedToolCallbackProvider) {
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(combinedToolCallbackProvider)
                .build();
    }

    /**
     * 处理baseToolChatClient。
     */
    @Bean("localToolChatClient")
    public ChatClient localToolChatClient(ChatModel chatModel,
                                         @Qualifier("localToolCallbackProvider") ToolCallbackProvider localToolCallbackProvider) {
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(localToolCallbackProvider)
                .build();
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean(name = "supervisorAsyncExecutor")
    public ThreadPoolTaskExecutor supervisorAsyncExecutor(DistributedAgentProperties properties) {
        DistributedAgentProperties.AsyncRuntimeProperties async = properties.getAsyncRuntime();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int maxConcurrency = Math.max(1, async.getMaxConcurrency());
        executor.setCorePoolSize(maxConcurrency);
        executor.setMaxPoolSize(maxConcurrency);
        executor.setQueueCapacity(Math.max(0, async.getQueueCapacity()));
        executor.setThreadNamePrefix("supervisor-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
