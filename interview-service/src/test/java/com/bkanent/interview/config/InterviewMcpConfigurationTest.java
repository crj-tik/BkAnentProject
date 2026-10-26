package com.bkanent.interview.config;

import com.bkanent.common.tool.McpTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewMcpConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(InterviewMcpConfiguration.class)
            .withBean("interviewMcpTools", McpTool.class, TestMcpTool::new);

    @Test
    void doesNotRegisterInterviewToolsWithoutTheServerProofGuard() {
        runner.run(context -> assertThat(context).doesNotHaveBean("mcpToolCallbackProvider"));
    }

    @Test
    void registersOfficialProviderOnlyWhenTheServerProofGuardIsEnabled() {
        runner.withPropertyValues("mcp.authorization.server.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ToolCallbackProvider.class);
            assertThat(context).hasBean("mcpToolCallbackProvider");
        });
    }

    private static final class TestMcpTool implements McpTool {
        @Tool
        public String ping() { return "pong"; }
    }
}
