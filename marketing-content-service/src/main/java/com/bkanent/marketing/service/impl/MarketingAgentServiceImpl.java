package com.bkanent.marketing.service.impl;

import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.marketing.model.MarketingContentDetailResponse;
import com.bkanent.marketing.model.MarketingContentUpsertRequest;
import com.bkanent.marketing.model.MarketingPublishStatusUpdateRequest;
import com.bkanent.marketing.service.MarketingAgentService;
import com.bkanent.marketing.service.MarketingAssetService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MarketingAgentServiceImpl implements MarketingAgentService {

    private static final String AGENT_ID = "marketing-agent";

    private final MarketingAssetService marketingAssetService;

    public MarketingAgentServiceImpl(MarketingAssetService marketingAssetService) {
        this.marketingAssetService = marketingAssetService;
    }

    @Override
    public AgentCard getAgentCard() {
        return new AgentCard(
                AGENT_ID,
                "Marketing Agent",
                "Responsible for marketing copy generation, publish preparation and publish execution",
                "1.0.0",
                List.of("marketing-copy", "marketing-adaptation", "marketing-publish"),
                List.of("marketing"),
                true,
                true,
                "/a2a",
                List.of("text", "json"),
                List.of("text", "json")
        );
    }

    @Override
    public AgentTaskInvokeResponse invoke(AgentTaskInvokeRequest request) {
        String intent = request.intent() == null ? "marketing.generate_copy" : request.intent();
        if ("marketing.publish_prepare".equalsIgnoreCase(intent)) {
            return preparePublish(request);
        }
        if ("marketing.publish".equalsIgnoreCase(intent)) {
            return publishContent(request);
        }
        return generateCopy(request);
    }

    private AgentTaskInvokeResponse generateCopy(AgentTaskInvokeRequest request) {
        String platform = resolveText(request, "platform", "通用平台");
        String tone = resolveText(request, "tone", "真实、专业、有吸引力");
        String listingTitle = resolveText(request, "listingTitle", "精选房源");
        String instruction = StringUtils.hasText(request.instruction()) ? request.instruction().trim() : "生成一版营销文案";
        String feedback = resolveOptionalText(request, "approvalFeedback");
        int retryCount = resolveInt(request, "retryCount", 0);
        String draftText = buildDraftText(platform, tone, listingTitle, instruction, feedback, retryCount);
        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("contentType", "copy_draft");
        structuredOutput.put("artifactTypeHint", "copy_draft_body");
        structuredOutput.put("platform", platform);
        structuredOutput.put("draftText", draftText);
        structuredOutput.put("retryCount", retryCount);
        if (StringUtils.hasText(feedback)) {
            structuredOutput.put("appliedFeedback", feedback);
        }
        return new AgentTaskInvokeResponse(
                request.sessionId(),
                request.taskId(),
                AGENT_ID,
                "completed",
                structuredOutput,
                List.of(),
                List.of("marketing.publish_prepare", "marketing.adapt_content"),
                "已生成一版可供审批的营销文案。",
                request.traceId()
        );
    }

    private AgentTaskInvokeResponse preparePublish(AgentTaskInvokeRequest request) {
        MarketingContentDetailResponse content = marketingAssetService.createContent(new MarketingContentUpsertRequest(
                resolveLong(request, "listingId", 0L),
                resolveText(request, "platform", "DOUYIN"),
                resolveText(request, "title", resolveText(request, "listingTitle", "营销内容待发布")),
                resolveText(request, "contentType", "TEXT"),
                resolveText(request, "draftText", resolveText(request, "copywriting", "待发布内容")),
                List.of(),
                resolveOptionalText(request, "coverImageUrl"),
                resolveOptionalText(request, "videoUrl"),
                resolveText(request, "platformVariant", "DEFAULT"),
                resolveTags(request),
                resolveText(request, "auditStatus", "APPROVED"),
                resolveLongObject(request, "parentContentId")
        ));

        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("contentType", "publish_payload");
        structuredOutput.put("artifactTypeHint", "publish_payload_body");
        structuredOutput.put("contentId", content.id());
        structuredOutput.put("listingId", content.listingId());
        structuredOutput.put("platform", content.platform());
        structuredOutput.put("title", content.title());
        structuredOutput.put("copywriting", content.copywriting());
        structuredOutput.put("coverImageUrl", content.coverImageUrl());
        structuredOutput.put("videoUrl", content.videoUrl());
        structuredOutput.put("publishStatus", content.publishStatus());
        structuredOutput.put("publishPayload", Map.of(
                "contentId", content.id(),
                "platform", content.platform(),
                "title", content.title(),
                "copywriting", content.copywriting(),
                "videoUrl", nullToEmpty(content.videoUrl())
        ));

        return new AgentTaskInvokeResponse(
                request.sessionId(),
                request.taskId(),
                AGENT_ID,
                "completed",
                structuredOutput,
                List.of(),
                List.of("marketing.publish", "notification.send"),
                "已生成发布准备数据，可进入发布执行。",
                request.traceId()
        );
    }

    private AgentTaskInvokeResponse publishContent(AgentTaskInvokeRequest request) {
        Long contentId = resolveLongObject(request, "contentId");
        if (contentId == null) {
            throw new IllegalArgumentException("contentId must not be blank for marketing.publish");
        }
        String externalPublishId = "pub-" + UUID.randomUUID();
        MarketingContentDetailResponse content = marketingAssetService.updatePublishStatus(
                contentId,
                new MarketingPublishStatusUpdateRequest(
                        resolveText(request, "publishStatus", "SUCCESS"),
                        resolveText(request, "publishMessage", "Published by marketing-agent"),
                        externalPublishId,
                        LocalDateTime.now()
                )
        );

        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("contentType", "publish_result");
        structuredOutput.put("artifactTypeHint", "publish_result");
        structuredOutput.put("contentId", content.id());
        structuredOutput.put("platform", content.platform());
        structuredOutput.put("publishStatus", content.publishStatus());
        structuredOutput.put("publishMessage", content.publishMessage());
        structuredOutput.put("externalPublishId", content.externalPublishId());
        structuredOutput.put("publishedAt", content.publishTime() == null ? null : content.publishTime().toString());

        return new AgentTaskInvokeResponse(
                request.sessionId(),
                request.taskId(),
                AGENT_ID,
                "completed",
                structuredOutput,
                List.of(),
                List.of("notification.send"),
                "营销内容已发布，外部发布单号 " + content.externalPublishId(),
                request.traceId()
        );
    }

    private String buildDraftText(String platform,
                                  String tone,
                                  String listingTitle,
                                  String instruction,
                                  String feedback,
                                  int retryCount) {
        String base = "【" + platform + "】" + listingTitle + "，重点突出核心卖点与实际居住价值，整体语气保持" + tone + "。";
        String body = "本次任务目标：" + instruction + "。文案建议围绕区位、通勤、户型利用率、居住体验和价格匹配度展开。";
        String revision = StringUtils.hasText(feedback)
                ? "根据反馈已进行第 " + retryCount + " 次调整，重点修正：" + feedback + "。"
                : "当前为首版草稿，可继续按反馈调整细节。";
        return String.join("\n", base, body, revision);
    }

    private List<String> resolveTags(AgentTaskInvokeRequest request) {
        Object value = request.structuredContext() == null ? null : request.structuredContext().get("tags");
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of("agent-generated");
    }

    private String resolveText(AgentTaskInvokeRequest request, String key, String defaultValue) {
        String value = resolveOptionalText(request, key);
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private String resolveOptionalText(AgentTaskInvokeRequest request, String key) {
        if (request.structuredContext() == null) {
            return null;
        }
        Object value = request.structuredContext().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private int resolveInt(AgentTaskInvokeRequest request, String key, int defaultValue) {
        if (request.structuredContext() == null) {
            return defaultValue;
        }
        Object value = request.structuredContext().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return defaultValue;
    }

    private long resolveLong(AgentTaskInvokeRequest request, String key, long defaultValue) {
        Long value = resolveLongObject(request, key);
        return value == null ? defaultValue : value;
    }

    private Long resolveLongObject(AgentTaskInvokeRequest request, String key) {
        if (request.structuredContext() == null) {
            return null;
        }
        Object value = request.structuredContext().get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
