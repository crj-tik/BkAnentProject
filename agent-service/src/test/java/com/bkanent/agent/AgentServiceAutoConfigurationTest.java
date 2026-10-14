package com.bkanent.agent;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration;
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
    void productionAutoConfigurationKeepsDeepSeekWithoutCreatingUnusedDashScopeAgents() {
        var selector = new ApplicationSelector();
        selector.setBeanClassLoader(AgentServiceApplication.class.getClassLoader());
        selector.setResourceLoader(new DefaultResourceLoader());
        selector.setEnvironment(new StandardEnvironment());
        selector.setBeanFactory(new DefaultListableBeanFactory());

        var selected = selector.selected(AnnotationMetadata.introspect(AgentServiceApplication.class));

        assertThat(selected).contains("org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration");
        assertThat(selected).doesNotContain(DashScopeChatAutoConfiguration.class.getName(),
                DashScopeAgentAutoConfiguration.class.getName());
    }

    private static final class ApplicationSelector extends AutoConfigurationImportSelector {
        List<String> selected(AnnotationMetadata metadata) {
            return getAutoConfigurationEntry(metadata).getConfigurations();
        }
    }
}
