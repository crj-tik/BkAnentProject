package com.bkanent.media.tool;

import com.bkanent.common.model.MediaGenerateTaskRequest;
import com.bkanent.common.model.MediaTaskResultDTO;
import com.bkanent.media.service.MediaGenerationTaskService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MediaTools {

    private final MediaGenerationTaskService mediaGenerationTaskService;

    public MediaTools(MediaGenerationTaskService mediaGenerationTaskService) {
        this.mediaGenerationTaskService = mediaGenerationTaskService;
    }

    @Tool(description = "Submit a video generation task for a listing. Returns the media task ID for tracking.")
    public String submitMediaTask(
            @ToolParam(description = "Listing ID to generate media for") Long listingId,
            @ToolParam(description = "Video generation prompt describing the desired output") String prompt) {
        return mediaGenerationTaskService.submitTask(new MediaGenerateTaskRequest(
                listingId, null, "media-agent", "VIDEO_GENERATION", prompt,
                List.of("cover", "interior", "community", "closing-shot"), 4));
    }

    @Tool(description = "Query the status and result of a media generation task by its task ID.")
    public MediaTaskResultDTO getTaskResult(
            @ToolParam(description = "Media task ID returned from submitMediaTask") String taskId) {
        return mediaGenerationTaskService.getTaskResult(taskId);
    }
}