package com.bkanent.agent;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioSpeechAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioTranscriptionAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeImageAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeMultimodalEmbeddingAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeRerankAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeVideoAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AgentServiceApplication 应用入口类。
 */
@MapperScan("com.bkanent.agent.mapper")
@EnableScheduling
@EnableAsync
@SpringBootApplication(exclude = {DashScopeChatAutoConfiguration.class, DashScopeAgentAutoConfiguration.class,
        DashScopeAudioSpeechAutoConfiguration.class, DashScopeAudioTranscriptionAutoConfiguration.class,
        DashScopeImageAutoConfiguration.class, DashScopeVideoAutoConfiguration.class,
        DashScopeEmbeddingAutoConfiguration.class, DashScopeMultimodalEmbeddingAutoConfiguration.class,
        DashScopeRerankAutoConfiguration.class})
public class AgentServiceApplication {

    /**
     * 启动应用。
     */
    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}
