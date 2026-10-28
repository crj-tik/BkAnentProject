package com.bkanent.businessreview.web;

import com.bkanent.businessreview.domain.ReviewDomain.*;
import com.bkanent.businessreview.service.ReviewApplicationService;
import com.bkanent.businessreview.service.ReviewApplicationService.CursorPage;
import com.bkanent.common.model.ApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/business-review/reviews")
public class ReviewController {
    private final ReviewApplicationService service;
    public ReviewController(ReviewApplicationService service) { this.service = service; }

    @PostMapping
    public ApiResponse<ReviewMatter> create(@RequestBody CreateReviewRequest request) {
        return ApiResponse.ok(service.create(request.storeId(), request.datasetId()));
    }

    @GetMapping
    public ApiResponse<CursorPage<ReviewMatter>> list(@RequestParam Long storeId,
                                                       @RequestParam(required = false) String cursor,
                                                       @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.list(storeId, cursor, limit));
    }

    @GetMapping("/{reviewId}")
    public ApiResponse<ReviewMatter> get(@PathVariable String reviewId) {
        return ApiResponse.ok(service.get(reviewId));
    }

    @GetMapping("/{reviewId}/plans")
    public ApiResponse<List<ReviewPlan>> plans(@PathVariable String reviewId) {
        return ApiResponse.ok(service.listPlans(reviewId));
    }

    @PostMapping("/{reviewId}/plans")
    public ApiResponse<ReviewPlan> writePlan(@PathVariable String reviewId,
                                             @RequestBody WritePlanRequest request) {
        return ApiResponse.ok(service.writePlan(reviewId, request.expectedMatterVersion(),
                request.expectedDiagnosisRevision(), request.dueAt(), request.actions()));
    }

    @PostMapping("/{reviewId}/plans/confirm")
    public ApiResponse<ConfirmationReceipt> confirm(@PathVariable String reviewId,
                                                    @RequestBody ConfirmPlanRequest request) {
        return ApiResponse.ok(service.confirm(reviewId, request.expectedPlanVersion(),
                request.requestId(), request.parameterHash()));
    }

    @GetMapping("/{reviewId}/actions")
    public ApiResponse<List<ReviewAction>> actions(@PathVariable String reviewId) {
        return ApiResponse.ok(service.listActions(reviewId));
    }

    @PostMapping("/{reviewId}/actions/{actionId}/feedback")
    public ApiResponse<ReviewAction> feedback(@PathVariable String reviewId, @PathVariable String actionId,
                                              @RequestBody ActionFeedbackRequest request) {
        return ApiResponse.ok(service.feedback(reviewId, actionId, request.status(),
                request.evidenceLevel(), request.note(), request.expectedActionVersion(), request.requestId()));
    }

    @GetMapping("/{reviewId}/observations")
    public ApiResponse<ReviewObservation> observations(@PathVariable String reviewId) {
        return ApiResponse.ok(service.observe(reviewId));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateReviewRequest(Long storeId, String datasetId) { }
    public record WritePlanRequest(long expectedMatterVersion, int expectedDiagnosisRevision,
                                   Instant dueAt, List<String> actions) { }
    public record ConfirmPlanRequest(int expectedPlanVersion, String requestId, String parameterHash) { }
    public record ActionFeedbackRequest(String requestId, long expectedActionVersion, ActionStatus status,
                                       EvidenceLevel evidenceLevel, String note) { }
}
