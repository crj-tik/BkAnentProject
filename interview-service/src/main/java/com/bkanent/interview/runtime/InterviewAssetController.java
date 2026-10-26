package com.bkanent.interview.runtime;

import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.interview.entity.InterviewAssetEntity;
import com.bkanent.interview.service.InterviewAssetService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Authenticated REST lifecycle for private transcript submission and explicit publication. */
@RestController
@RequestMapping("/interviews/assets")
public class InterviewAssetController {
    private final InterviewAssetService assetService;

    public InterviewAssetController(InterviewAssetService assetService) { this.assetService = assetService; }

    public record SubmitTranscriptRequest(Long caseId, String title, String content) { }

    @PostMapping
    public ApiResponse<Map<String, Object>> submit(@RequestBody SubmitTranscriptRequest request) {
        try {
            if (request == null) return ApiResponse.fail("INTERVIEW_ASSET_INVALID", "逐字稿请求无效");
            InterviewAssetEntity asset = assetService.submitTranscript(request.caseId(), request.title(), request.content());
            return ApiResponse.ok(view(asset));
        } catch (AuthzException denied) {
            return ApiResponse.fail(denied.code().name(), "当前身份不能提交此访谈资料");
        } catch (IllegalArgumentException invalid) {
            return ApiResponse.fail("INTERVIEW_ASSET_INVALID", invalid.getMessage());
        }
    }

    @PostMapping("/{assetId}/publish")
    public ApiResponse<Map<String, Object>> publish(@PathVariable Long assetId) {
        try {
            return ApiResponse.ok(view(assetService.publish(assetId)));
        } catch (AuthzException denied) {
            return ApiResponse.fail(denied.code().name(), "当前身份不能发布此访谈资料");
        } catch (IllegalArgumentException invalid) {
            return ApiResponse.fail("INTERVIEW_ASSET_NOT_FOUND", "未找到可发布的访谈资料");
        }
    }

    @PostMapping("/{assetId}/revoke-publication")
    public ApiResponse<Map<String, Object>> revokePublication(@PathVariable Long assetId) {
        try {
            if (!assetService.revokePublication(assetId)) {
                return ApiResponse.fail("INTERVIEW_ASSET_CHANGED", "访谈资料已变更，请重新读取后操作");
            }
            return ApiResponse.ok(Map.of("assetId", assetId, "visibility", InterviewAssetService.PRIVATE));
        } catch (AuthzException denied) {
            return ApiResponse.fail(denied.code().name(), "当前身份不能撤回此访谈资料");
        } catch (IllegalArgumentException invalid) {
            return ApiResponse.fail("INTERVIEW_ASSET_NOT_FOUND", "未找到访谈资料");
        }
    }

    @PostMapping("/{assetId}/regrade")
    public ApiResponse<Map<String, Object>> regrade(@PathVariable Long assetId) {
        try {
            return ApiResponse.ok(view(assetService.regrade(assetId)));
        } catch (AuthzException denied) {
            return ApiResponse.fail(denied.code().name(), "当前身份不能重评级此访谈资料");
        } catch (IllegalArgumentException invalid) {
            return ApiResponse.fail("INTERVIEW_ASSET_NOT_FOUND", "未找到访谈资料");
        }
    }

    private Map<String, Object> view(InterviewAssetEntity asset) {
        return Map.of("assetId", asset.getId(), "caseId", asset.getCaseId(),
                "visibility", asset.getVisibility() == null ? InterviewAssetService.PRIVATE : asset.getVisibility(),
                "grade", asset.getArchiveGrade() == null ? "L0" : asset.getArchiveGrade(),
                "publicationRevision", asset.getPublicationRevision() == null ? 0 : asset.getPublicationRevision());
    }
}
