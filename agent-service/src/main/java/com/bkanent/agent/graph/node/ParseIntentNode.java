package com.bkanent.agent.graph.node;

import com.bkanent.agent.graph.SupervisorGraphNode;
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.model.distributed.WorkflowPlan;
import com.bkanent.agent.service.SupervisorIntentPlanningService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

@Component
public class ParseIntentNode implements SupervisorGraphNode {

    private final SupervisorIntentPlanningService supervisorIntentPlanningService;

    public ParseIntentNode(SupervisorIntentPlanningService supervisorIntentPlanningService) {
        this.supervisorIntentPlanningService = supervisorIntentPlanningService;
    }

    @Override
    public SupervisorGraphState apply(SupervisorGraphState state) {
        Map<String, Object> context = state.sharedContext();
        String message = state.userMessage() == null ? "" : state.userMessage();
        WorkflowPlan workflowPlan = supervisorIntentPlanningService.readPlan(context);
        if (workflowPlan != null) {
            return state.withIntent(
                    workflowPlan.intent(),
                    workflowPlan.domain(),
                    workflowPlan.workflowType()
            );
        }
        String domain = resolveDomain(context, message);
        String intent = resolveIntent(domain);
        String workflowType = resolveWorkflowType(domain, message, context);
        return state.withIntent(intent, domain, workflowType);
    }

    private String resolveDomain(Map<String, Object> context, String message) {
        if (context != null && StringUtils.hasText(String.valueOf(context.get("domain")))) {
            return String.valueOf(context.get("domain"));
        }
        if (containsContractIntent(message)) {
            return "contract";
        }
        if (containsNotificationIntent(message)) {
            return "notification";
        }
        if (containsSettlementIntent(message)) {
            return "settlement";
        }
        if (containsMarketingIntent(message)) {
            return "marketing";
        }
        if (containsTradeIntent(message)) {
            return "trade";
        }
        return "listing";
    }

    private String resolveIntent(String domain) {
        return switch (domain) {
            case "marketing" -> "marketing.generate_copy";
            case "media" -> "media.generate_video_task";
            case "trade" -> "trade.feasibility_analysis";
            case "contract" -> "contract.risk_review";
            case "notification" -> "notification.send";
            case "settlement" -> "settlement.prepare";
            default -> "listing.search";
        };
    }

    private String resolveWorkflowType(String domain, String message, Map<String, Object> context) {
        if (context != null && context.get("parallelDomains") instanceof java.util.Collection<?> collection && collection.size() > 1) {
            return "parallel";
        }
        if (Boolean.TRUE.equals(context == null ? null : context.get("requireApproval"))) {
            return domain + "_with_approval";
        }
        if (containsMarketingIntent(message)) {
            return "marketing_pipeline";
        }
        return "single_agent";
    }

    private boolean containsMarketingIntent(String message) {
        return message.contains("文案")
                || message.contains("营销")
                || message.contains("广告")
                || message.contains("推广")
                || message.contains("小红书")
                || message.contains("抖音");
    }

    private boolean containsTradeIntent(String message) {
        return message.contains("交易")
                || message.contains("成交")
                || message.contains("风险")
                || message.contains("可行性")
                || message.contains("trade");
    }

    private boolean containsContractIntent(String message) {
        return message.contains("合同")
                || message.contains("签约")
                || message.contains("归档")
                || message.contains("ocr")
                || message.contains("OCR")
                || message.contains("contract");
    }

    private boolean containsSettlementIntent(String message) {
        return message.contains("结算")
                || message.contains("佣金")
                || message.contains("出款")
                || message.contains("打款")
                || message.contains("settlement");
    }

    private boolean containsNotificationIntent(String message) {
        return message.contains("通知")
                || message.contains("提醒")
                || message.contains("消息")
                || message.contains("notification");
    }
}
