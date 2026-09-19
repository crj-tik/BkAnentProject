package com.bkanent.agent.config;

import com.bkanent.common.mcp.DynamicMcpClientManager;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

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
            @Qualifier("mcpToolCallbacks") ToolCallbackProvider mcpToolCallbacks,
            DynamicMcpClientManager dynamicMcpClientManager) {
        return () -> {
            ToolCallback[] localCallbacks = localToolCallbackProvider.getToolCallbacks();
            ToolCallback[] mcpCallbacks = mcpToolCallbacks.getToolCallbacks();
            ToolCallback[] dynamicCallbacks = dynamicMcpClientManager.getDynamicToolCallbacks();
            Map<String, ToolCallback> callbacksByName = new LinkedHashMap<>();
            addCallbacks(callbacksByName, localCallbacks);
            addCallbacks(callbacksByName, mcpCallbacks);
            addCallbacks(callbacksByName, dynamicCallbacks);
            return callbacksByName.values().toArray(new ToolCallback[0]);
        };
    }

    private void addCallbacks(Map<String, ToolCallback> callbacksByName, ToolCallback[] callbacks) {
        if (callbacks == null) {
            return;
        }
        for (ToolCallback callback : callbacks) {
            if (callback != null && callback.getToolDefinition() != null) {
                callbacksByName.putIfAbsent(callback.getToolDefinition().name(), callback);
            }
        }
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
