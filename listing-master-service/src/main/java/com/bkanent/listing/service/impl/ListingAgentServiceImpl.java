package com.bkanent.listing.service.impl;

import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.model.ListingKeywordSearchRequest;
import com.bkanent.common.model.ListingKeywordSearchResultDTO;
import com.bkanent.listing.service.ListingAgentService;
import com.bkanent.listing.service.ListingManagementService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ListingAgentServiceImpl 房源 Agent 服务实现。
 */
@Service
public class ListingAgentServiceImpl implements ListingAgentService {

    private static final String AGENT_ID = "listing-agent";

    private final ListingManagementService listingManagementService;

    public ListingAgentServiceImpl(ListingManagementService listingManagementService) {
        this.listingManagementService = listingManagementService;
    }

    @Override
    public AgentCard getAgentCard() {
        return new AgentCard(
                AGENT_ID,
                "Listing Agent",
                "负责房源检索、推荐与摘要",
                "1.0.0",
                List.of("listing-search", "listing-recommendation"),
                List.of("listing"),
                true,
                true,
                "/a2a",
                List.of("text", "json"),
                List.of("text", "json")
        );
    }

    @Override
    public AgentTaskInvokeResponse invoke(AgentTaskInvokeRequest request) {
        String keyword = resolveKeyword(request);
        int topK = resolveTopK(request);
        List<ListingKeywordSearchResultDTO> candidates = listingManagementService.searchListingSummariesByKeyword(
                new ListingKeywordSearchRequest(keyword, null, null, null, null, null, null, topK)
        );
        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("keyword", keyword);
        structuredOutput.put("listingCount", candidates.size());
        structuredOutput.put("listings", candidates.stream()
                .map(candidate -> Map.of(
                        "listing", candidate.listing(),
                        "score", candidate.score()
                ))
                .toList());
        return new AgentTaskInvokeResponse(
                request.sessionId(),
                request.taskId(),
                AGENT_ID,
                "completed",
                structuredOutput,
                List.of(),
                List.of("listing.summary"),
                buildSummary(keyword, candidates.size()),
                request.traceId()
        );
    }

    private String resolveKeyword(AgentTaskInvokeRequest request) {
        if (request.structuredContext() != null) {
            Object keyword = request.structuredContext().get("keyword");
            if (keyword instanceof String text && StringUtils.hasText(text)) {
                return text.trim();
            }
        }
        return request.instruction() == null ? "" : request.instruction().trim();
    }

    private int resolveTopK(AgentTaskInvokeRequest request) {
        if (request.structuredContext() != null) {
            Object topK = request.structuredContext().get("topK");
            if (topK instanceof Number number) {
                return Math.max(1, Math.min(number.intValue(), 20));
            }
        }
        return 5;
    }

    private String buildSummary(String keyword, int count) {
        if (!StringUtils.hasText(keyword)) {
            return "已完成房源候选检索，共返回 " + count + " 条结果。";
        }
        return "已根据“" + keyword + "”完成房源候选检索，共返回 " + count + " 条结果。";
    }
}
