package com.bkanent.knowledge.api;

public record KnowledgeReviewRequest(String requestId, Long expectedVersion,
                                     String decision, String reason) { }
