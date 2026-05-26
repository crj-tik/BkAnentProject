package com.bkanent.business.service.impl;

import com.bkanent.business.service.TradeAgentService;
import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TradeAgentServiceImpl implements TradeAgentService {

    @Override
    public AgentCard getAgentCard() {
        return new AgentCard(
                "trade-agent",
                "Trade Agent",
                "Responsible for transaction feasibility analysis and risk reasoning",
                "1.0.0",
                List.of("trade-feasibility", "trade-risk-reasoning"),
                List.of("trade"),
                true,
                true,
                "/a2a",
                List.of("text", "json"),
                List.of("text", "json")
        );
    }

    @Override
    public AgentTaskInvokeResponse invoke(AgentTaskInvokeRequest request) {
        Map<String, Object> context = request.structuredContext() == null ? Map.of() : request.structuredContext();
        Map<String, Object> assessment = new LinkedHashMap<>();
        assessment.put("resultType", "trade_assessment");
        assessment.put("decision", resolveDecision(context, request.instruction()));
        assessment.put("reasons", List.of(
                "listing facts reviewed",
                "budget and workflow checked",
                "current information supports a first-pass judgement"
        ));
        assessment.put("structuredAssessment", Map.of(
                "riskLevel", resolveRiskLevel(context),
                "needsMoreDocuments", Boolean.TRUE.equals(context.get("needsMoreDocuments")),
                "retryCount", context.getOrDefault("retryCount", 0)
        ));
        assessment.put("contentType", "trade_assessment");
        return new AgentTaskInvokeResponse(
                request.sessionId(),
                request.taskId(),
                "trade-agent",
                "COMPLETED",
                assessment,
                List.of(),
                List.of("contract.review", "settlement.prepare"),
                "Trade assessment finished with decision " + assessment.get("decision"),
                request.traceId()
        );
    }

    private String resolveDecision(Map<String, Object> context, String instruction) {
        if (Boolean.TRUE.equals(context.get("needsMoreDocuments"))) {
            return "MANUAL_REVIEW";
        }
        if (instruction != null && (instruction.contains("风险") || instruction.contains("risk"))) {
            return "RISK_ALERT";
        }
        return "PROCEED";
    }

    private String resolveRiskLevel(Map<String, Object> context) {
        if (Boolean.TRUE.equals(context.get("needsMoreDocuments"))) {
            return "medium";
        }
        Object listingCount = context.get("listingCount");
        if (listingCount instanceof Number number && number.intValue() == 0) {
            return "high";
        }
        return "low";
    }
}
