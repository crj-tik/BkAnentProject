package com.bkanent.knowledge.api;

public record KnowledgeOperationRequest(String requestId, Long expectedVersion) { }
