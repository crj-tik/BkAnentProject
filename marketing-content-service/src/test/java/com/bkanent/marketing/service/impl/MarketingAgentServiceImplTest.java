package com.bkanent.marketing.service.impl;

import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.marketing.config.MarketingAgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MarketingAgentServiceImplTest {

    @Test
    void fabricatedPublishedModelOutputCannotChangeStatusOrClaimPublication() {
        var service = new MarketingAgentServiceImpl(mock(ChatClient.class), mock(ChatModel.class),
                new MarketingAgentProperties(), new ObjectMapper());
        var request = new AgentTaskInvokeRequest("session-1", "task-1", null, "trace-1", "supervisor-agent",
                "marketing-agent", null, "marketing", "create and publish", Map.of(), List.of(), List.of(),
                "json", "request-1", false);

        var response = service.buildResponse(request, Map.of(
                "intent", "publish",
                "decision", "PUBLISHED",
                "contentId", 5,
                "platform", "DOUYIN",
                "publishStatus", "PUBLISHED",
                "draftText", "可核对草稿正文",
                "summary", "已发布并已通知"
        ));

        assertThat(response.structuredOutput())
                .containsEntry("intent", "generate_copy")
                .containsEntry("decision", "SUCCESS")
                .containsEntry("publishStatus", "DRAFT")
                .containsEntry("summary", "营销 Agent 仅生成草稿或准备发布计划；当前内容状态为 DRAFT，尚未发布。发布须由 Supervisor 单独确认并以领域回执为准。");
        assertThat(response.nextHints()).doesNotContain("notification.send", "marketing.publish", "marketing.confirm");
    }
}
