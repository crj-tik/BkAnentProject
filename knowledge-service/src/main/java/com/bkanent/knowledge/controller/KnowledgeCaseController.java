package com.bkanent.knowledge.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.knowledge.api.CreateKnowledgeRequest;
import com.bkanent.knowledge.api.EditKnowledgeRequest;
import com.bkanent.knowledge.api.GenerateKnowledgeRequest;
import com.bkanent.knowledge.api.KnowledgeGenerationStatus;
import com.bkanent.knowledge.api.KnowledgeMutationReceipt;
import com.bkanent.knowledge.api.KnowledgeCaseDetail;
import com.bkanent.knowledge.api.KnowledgeOperationRequest;
import com.bkanent.knowledge.api.KnowledgeReviewRequest;
import com.bkanent.knowledge.service.KnowledgeLifecycleService;
import com.bkanent.knowledge.service.KnowledgeGenerationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/knowledge/v1/cases")
public class KnowledgeCaseController {
    private final KnowledgeLifecycleService service;
    private final KnowledgeGenerationService generationService;

    public KnowledgeCaseController(KnowledgeLifecycleService service, KnowledgeGenerationService generationService) {
        this.service = service;
        this.generationService = generationService;
    }

    @PostMapping
    public ApiResponse<KnowledgeMutationReceipt> create(@RequestBody CreateKnowledgeRequest request) {
        return ApiResponse.ok(service.create(request));
    }
    @GetMapping("/{knowledgeId}")
    public ApiResponse<KnowledgeCaseDetail> read(@PathVariable String knowledgeId) {
        return ApiResponse.ok(service.read(knowledgeId));
    }
    @PostMapping("/{knowledgeId}/edit")
    public ApiResponse<KnowledgeMutationReceipt> edit(@PathVariable String knowledgeId, @RequestBody EditKnowledgeRequest request) {
        return ApiResponse.ok(service.edit(knowledgeId, request));
    }
    @PostMapping("/{knowledgeId}/submit")
    public ApiResponse<KnowledgeMutationReceipt> submit(@PathVariable String knowledgeId, @RequestBody KnowledgeOperationRequest request) {
        return ApiResponse.ok(service.submit(knowledgeId, request));
    }
    @PostMapping("/{knowledgeId}/generate")
    public ApiResponse<KnowledgeGenerationStatus> generate(@PathVariable String knowledgeId, @RequestBody GenerateKnowledgeRequest request) {
        return ApiResponse.ok(generationService.generate(knowledgeId, request));
    }
    @GetMapping("/{knowledgeId}/generation/{taskId}")
    public ApiResponse<KnowledgeGenerationStatus> generationStatus(@PathVariable String knowledgeId, @PathVariable String taskId) {
        return ApiResponse.ok(generationService.status(knowledgeId, taskId));
    }
    @PostMapping("/{knowledgeId}/review")
    public ApiResponse<KnowledgeMutationReceipt> review(@PathVariable String knowledgeId, @RequestBody KnowledgeReviewRequest request) {
        return ApiResponse.ok(service.review(knowledgeId, request));
    }
    @PostMapping("/{knowledgeId}/publish")
    public ApiResponse<KnowledgeMutationReceipt> publish(@PathVariable String knowledgeId, @RequestBody KnowledgeOperationRequest request) {
        return ApiResponse.ok(service.publish(knowledgeId, request));
    }
    @PostMapping("/{knowledgeId}/withdraw")
    public ApiResponse<KnowledgeMutationReceipt> withdraw(@PathVariable String knowledgeId, @RequestBody KnowledgeOperationRequest request) {
        return ApiResponse.ok(service.withdraw(knowledgeId, request));
    }
}
