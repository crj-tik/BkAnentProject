package com.bkanent.agent.service.impl;

import com.bkanent.agent.mcp.AgentMcpClient;
import com.bkanent.agent.mcp.model.AgentMcpCallResult;
import com.bkanent.agent.service.AgentChatService;
import com.bkanent.common.marketing.campaign.MarketingContentDraftResponse;
import com.bkanent.common.model.PublishRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentCapabilityServiceImplTest {

    private final AgentChatService chat = mock(AgentChatService.class);
    private final AgentMcpClient mcp = mock(AgentMcpClient.class);
    private final AgentCapabilityServiceImpl service = new AgentCapabilityServiceImpl(chat, mcp, new ObjectMapper());

    @Test
    void agentMarketingDraftUsesActualRequestIdentityAndSafeMcpTool() {
        when(chat.call(anyString(), anyString())).thenReturn("待审核文案");
        when(mcp.callTool(eq("marketing-mcp-server"), eq("createMarketingContentDraft"), anyMap()))
                .thenReturn(new AgentMcpCallResult("marketing-mcp-server", "createMarketingContentDraft", "",
                        Map.of("contents", List.of(
                                Map.of("contentId", 91, "platform", "DOUYIN", "status", "DRAFT"),
                                Map.of("contentId", 92, "platform", "XIAOHONGSHU", "status", "DRAFT")))));

        var result = service.createMarketingDraft(new PublishRequest(501L,
                List.of("DOUYIN", "XIAOHONGSHU"), "根据真实房源信息撰写"));

        assertThat(result).containsExactly(new MarketingContentDraftResponse(91L, "DOUYIN", "DRAFT"),
                new MarketingContentDraftResponse(92L, "XIAOHONGSHU", "DRAFT"));
        verify(chat).call(anyString(), eq("请根据以下需求生成一版中文房源营销文案草稿：\n根据真实房源信息撰写"));
        verify(mcp).callTool("marketing-mcp-server", "createMarketingContentDraft", Map.of(
                "listingId", 501L, "copywriting", "待审核文案", "platforms", "DOUYIN,XIAOHONGSHU"));
    }

    @Test
    void invalidDraftRequestStopsBeforeModelOrMcp() {
        assertThatThrownBy(() -> service.createMarketingDraft(new PublishRequest(null, List.of("DOUYIN"), "copy")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("listingId, platforms and prompt");
        verifyNoInteractions(chat, mcp);
    }
}
