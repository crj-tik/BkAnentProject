package com.bkanent.agent.graph.node;

import com.bkanent.common.agent.AgentTaskInvokeResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RouteDecisionNode {

    public RouteDecision evaluate(Map<String, Object> context, AgentTaskInvokeResponse response) {
        String strategyVersion = resolveStrategyVersion(context);
        String routeOverrideDomain = resolveRouteOverrideDomain(context);
        if (StringUtils.hasText(routeOverrideDomain)) {
            return new RouteDecision("handoff", routeOverrideDomain,
                    "Gray route strategy " + strategyVersion + " overrides next domain to " + routeOverrideDomain + ".",
                    strategyVersion);
        }
        Map<String, Object> output = response == null || response.structuredOutput() == null
                ? Map.of()
                : response.structuredOutput();
        Map<String, Object> tradeOutput = asMap(output.get("tradeOutput"));
        Map<String, Object> structuredAssessment = asMap(tradeOutput.get("structuredAssessment"));
        String decision = textOrDefault(tradeOutput.get("decision"), textOrDefault(output.get("tradeDecision"), "PROCEED"));
        boolean needsMoreDocuments = Boolean.TRUE.equals(structuredAssessment.get("needsMoreDocuments"));
        boolean forceContractReview = context != null && Boolean.TRUE.equals(context.get("forceContractReview"));
        if (forceContractReview) {
            return new RouteDecision("handoff", "contract", "Forced contract review after parallel analysis.", strategyVersion);
        }
        if ("MANUAL_REVIEW".equalsIgnoreCase(decision)) {
            return new RouteDecision("handoff", "contract", "Trade assessment requires manual contract review.", strategyVersion);
        }
        if ("RISK_ALERT".equalsIgnoreCase(decision)) {
            return new RouteDecision("handoff", "contract", "Trade assessment reported risk, handoff to contract review.", strategyVersion);
        }
        if (needsMoreDocuments) {
            return new RouteDecision("handoff", "contract", "Trade assessment requires more documents, contract review continues the flow.", strategyVersion);
        }
        return new RouteDecision("complete", null, "Listing and trade analysis completed without contract handoff.", strategyVersion);
    }

    public boolean shouldAutoRoute(Map<String, Object> context, AgentTaskInvokeResponse response) {
        if (!containsDomain(context, "listing") || !containsDomain(context, "trade")) {
            return false;
        }
        if (context != null && context.containsKey("autoRouteAfterParallel")) {
            return Boolean.TRUE.equals(context.get("autoRouteAfterParallel"));
        }
        return StringUtils.hasText(evaluate(context, response).nextDomain());
    }

    private boolean containsDomain(Map<String, Object> context, String expectedDomain) {
        if (context == null || !(context.get("parallelDomains") instanceof Collection<?> collection)) {
            return false;
        }
        return collection.stream().map(String::valueOf).anyMatch(expectedDomain::equals);
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> converted = new LinkedHashMap<>();
            mapValue.forEach((key, mapEntryValue) -> converted.put(String.valueOf(key), mapEntryValue));
            return converted;
        }
        return Map.of();
    }

    private String textOrDefault(Object value, String defaultValue) {
        return StringUtils.hasText(value == null ? null : String.valueOf(value))
                ? String.valueOf(value)
                : defaultValue;
    }

    private String resolveStrategyVersion(Map<String, Object> context) {
        if (context == null) {
            return "";
        }
        Object value = context.get("grayStrategyVersion");
        return value == null ? "" : String.valueOf(value);
    }

    private String resolveRouteOverrideDomain(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object overrides = context.get("routeOverrideDomains");
        if (!(overrides instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = map.get("parallel");
        return value == null || !StringUtils.hasText(String.valueOf(value)) ? null : String.valueOf(value);
    }

    public record RouteDecision(String action, String nextDomain, String summary, String strategyVersion) {
        public Map<String, Object> asMap() {
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("action", action);
            decision.put("nextDomain", nextDomain);
            decision.put("summary", summary);
            decision.put("strategyVersion", strategyVersion);
            decision.put("evaluatedAt", System.currentTimeMillis());
            return decision;
        }
    }
}
