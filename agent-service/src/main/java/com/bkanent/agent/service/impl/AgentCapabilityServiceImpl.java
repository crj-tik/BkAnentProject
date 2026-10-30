package com.bkanent.agent.service.impl;

import com.bkanent.agent.mcp.AgentMcpClient;
import com.bkanent.agent.mcp.AgentMcpNames;
import com.bkanent.agent.mcp.model.AgentMcpCallResult;
import com.bkanent.agent.service.AgentCapabilityService;
import com.bkanent.agent.service.AgentChatService;
import com.bkanent.common.model.PublishRequest;
import com.bkanent.common.marketing.campaign.MarketingContentDraftResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * AgentCapabilityServiceImpl 服务实现类。
 */
@Service
public class AgentCapabilityServiceImpl implements AgentCapabilityService {

    private static final String MARKETING_COPY_SYSTEM_PROMPT = """
            你是房产营销文案助手。
            请根据用户提供的需求生成中文房源营销文案草稿。只生成内容，不创建发布计划、不确认发布，也不发送通知。
            文案要真实、简洁、有吸引力，不允许夸大承诺。
            """;

    /**
     * 字段：deepSeekChatService。
     */
    private final AgentChatService agentChatService;
    /**
     * 字段：agentMcpClient。
     */
    private final AgentMcpClient agentMcpClient;
    /**
     * 字段：objectMapper。
     */
    private final ObjectMapper objectMapper;

    /**
     * 构造 AgentCapabilityServiceImpl 实例。
     */
    public AgentCapabilityServiceImpl(AgentChatService agentChatService,
                                      AgentMcpClient agentMcpClient,
                                      ObjectMapper objectMapper) {
        this.agentChatService = agentChatService;
        this.agentMcpClient = agentMcpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 发布listingByPrompt。
     */
    @Override
    public List<MarketingContentDraftResponse> createMarketingDraft(PublishRequest request) {
        if (request == null || request.listingId() == null || request.listingId() <= 0
                || request.platforms() == null || request.platforms().isEmpty()
                || request.platforms().stream().anyMatch(platform -> platform == null || platform.isBlank())
                || request.prompt() == null || request.prompt().isBlank()) {
            throw new IllegalArgumentException("listingId, platforms and prompt are required to create a marketing draft");
        }
        String copywriting = agentChatService.call(
                MARKETING_COPY_SYSTEM_PROMPT,
                "请根据以下需求生成一版中文房源营销文案草稿：\n" + request.prompt()
        );
        AgentMcpCallResult result = agentMcpClient.callTool(
                AgentMcpNames.MARKETING_SERVER,
                AgentMcpNames.CREATE_MARKETING_CONTENT_DRAFT,
                Map.of(
                        "listingId", request.listingId(),
                        "copywriting", copywriting,
                        "platforms", String.join(",", request.platforms())
                )
        );
        return readList(result.payload().get("contents"), new TypeReference<List<MarketingContentDraftResponse>>() {
        });
    }

    /**
     * 读取list。
     */
    private <T> List<T> readList(Object value, TypeReference<List<T>> typeReference) {
        if (value == null) {
            return List.of();
        }
        return objectMapper.convertValue(value, typeReference);
    }
}
