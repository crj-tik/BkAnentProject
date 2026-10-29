package com.bkanent.knowledge.api;

public record KnowledgeGenerationStatus(String taskId, String knowledgeId, String status,
                                        String revision, String errorCode) { }
