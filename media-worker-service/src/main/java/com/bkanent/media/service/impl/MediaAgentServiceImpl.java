package com.bkanent.media.service.impl;

import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.model.MediaGenerateTaskRequest;
import com.bkanent.media.service.MediaAgentService;
import com.bkanent.media.service.MediaGenerationTaskService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MediaAgentServiceImpl implements MediaAgentService {

    private static final String AGENT_ID = "media-agent";

    private final MediaGenerationTaskService mediaGenerationTaskService;

    public MediaAgentServiceImpl(MediaGenerationTaskService mediaGenerationTaskService) {
        this.mediaGenerationTaskService = mediaGenerationTaskService;
    }

    @Override
    public AgentCard getAgentCard() {
        return new AgentCard(
                AGENT_ID,
                "Media Agent",
                "Responsible for media generation tasks, preview assets and publish-ready media references",
                "1.0.0",
                List.of("media-video-task", "media-result-query", "media-cover-prepare"),
                List.of("media"),
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
        String prompt = resolvePrompt(request, context);
        Long listingId = resolveLong(context.get("listingId"), 0L);
        Long contentId = resolveLongObject(context.get("contentId"));

        String mediaTaskId = mediaGenerationTaskService.submitTask(new MediaGenerateTaskRequest(
                listingId,
                contentId,
                "agent-service",
                "VIDEO_GENERATION",
                prompt,
                List.of("cover", "interior", "community", "closing-shot"),
                4
        ));

        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("contentType", "video_task");
        structuredOutput.put("artifactTypeHint", "media_task_detail");
        structuredOutput.put("mediaTaskId", mediaTaskId);
        structuredOutput.put("prompt", prompt);
        structuredOutput.put("status", "QUEUED");
        structuredOutput.put("publishReady", Boolean.TRUE);
        structuredOutput.put("coverImageUrl", "https://media.local/preview/" + mediaTaskId + "/cover.jpg");
        structuredOutput.put("videoUrl", "https://media.local/preview/" + mediaTaskId + "/video.mp4");
        structuredOutput.put("generatedAt", LocalDateTime.now().toString());

        return new AgentTaskInvokeResponse(
                request.sessionId(),
                request.taskId(),
                AGENT_ID,
                "COMPLETED",
                structuredOutput,
                List.of(),
                List.of("marketing.publish_prepare", "media.result_query"),
                "已创建媒体生成任务，并补齐发布准备所需的封面与视频引用。",
                request.traceId()
        );
    }

    private String resolvePrompt(AgentTaskInvokeRequest request, Map<String, Object> context) {
        Object prompt = context.get("videoPrompt");
        if (prompt instanceof String value && StringUtils.hasText(value)) {
            return value.trim();
        }
        Object copywriting = context.get("copywriting");
        if (copywriting instanceof String value && StringUtils.hasText(value)) {
            return "基于以下营销文案生成短视频脚本与镜头建议：" + value.trim();
        }
        if (StringUtils.hasText(request.instruction())) {
            return request.instruction().trim();
        }
        return "生成适用于房产推广的短视频脚本与封面。";
    }

    private Long resolveLong(Object value, Long defaultValue) {
        Long parsed = resolveLongObject(value);
        return parsed == null ? defaultValue : parsed;
    }

    private Long resolveLongObject(Object value) {
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
}
