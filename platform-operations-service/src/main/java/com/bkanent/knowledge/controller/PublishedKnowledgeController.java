package com.bkanent.knowledge.controller;

import com.bkanent.common.knowledge.PublishedBatchReadData;
import com.bkanent.common.knowledge.PublishedBatchReadRequest;
import com.bkanent.common.knowledge.PublishedCheckData;
import com.bkanent.common.knowledge.PublishedCheckRequest;
import com.bkanent.common.knowledge.PublishedSearchData;
import com.bkanent.common.knowledge.PublishedSearchRequest;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.knowledge.service.PublishedKnowledgeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/knowledge/v1/published")
public class PublishedKnowledgeController {
    private final PublishedKnowledgeService service;

    public PublishedKnowledgeController(PublishedKnowledgeService service) { this.service = service; }

    @PostMapping("/search")
    public ApiResponse<PublishedSearchData> search(@RequestBody PublishedSearchRequest request) {
        return ApiResponse.ok(service.search(request));
    }

    @PostMapping("/batch-read")
    public ApiResponse<PublishedBatchReadData> batchRead(@RequestBody PublishedBatchReadRequest request) {
        if (request == null || request.contractVersion() == null
                || !com.bkanent.common.knowledge.KnowledgeContract.VERSION.equals(request.contractVersion())) {
            throw new com.bkanent.knowledge.domain.KnowledgeOperationException("UNSUPPORTED_CONTRACT_VERSION", 400);
        }
        return ApiResponse.ok(service.batchRead(request.refs()));
    }

    @PostMapping("/check")
    public ApiResponse<PublishedCheckData> check(@RequestBody PublishedCheckRequest request) {
        return ApiResponse.ok(service.check(request));
    }
}
