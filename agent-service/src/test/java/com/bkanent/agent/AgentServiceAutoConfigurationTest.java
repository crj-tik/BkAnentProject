package com.bkanent.agent;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAsyncToolCallingManagerAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioSpeechAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioTranscriptionAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeImageAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeMultimodalEmbeddingAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeRerankAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeVideoAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationImportSelector;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.AnnotationMetadata;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentServiceAutoConfigurationTest {

    @Test
    void productionAutoConfigurationKeepsDeepSeekWithoutCreatingUnusedDashScopeModels() {
        var selector = new ApplicationSelector();
        selector.setBeanClassLoader(AgentServiceApplication.class.getClassLoader());
        selector.setResourceLoader(new DefaultResourceLoader());
        selector.setEnvironment(new StandardEnvironment());
        selector.setBeanFactory(new DefaultListableBeanFactory());

        var selected = selector.selected(AnnotationMetadata.introspect(AgentServiceApplication.class));

        assertThat(selected).contains("org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration");
        assertThat(selected).contains(DashScopeAsyncToolCallingManagerAutoConfiguration.class.getName());
        assertThat(selected).doesNotContain(DashScopeChatAutoConfiguration.class.getName(),
                DashScopeAgentAutoConfiguration.class.getName(), DashScopeAudioSpeechAutoConfiguration.class.getName(),
                DashScopeAudioTranscriptionAutoConfiguration.class.getName(), DashScopeImageAutoConfiguration.class.getName(),
                DashScopeVideoAutoConfiguration.class.getName(), DashScopeEmbeddingAutoConfiguration.class.getName(),
                DashScopeMultimodalEmbeddingAutoConfiguration.class.getName(), DashScopeRerankAutoConfiguration.class.getName());
    }

    private static final class ApplicationSelector extends AutoConfigurationImportSelector {
        List<String> selected(AnnotationMetadata metadata) {
            return getAutoConfigurationEntry(metadata).getConfigurations();
        }
    }
}
