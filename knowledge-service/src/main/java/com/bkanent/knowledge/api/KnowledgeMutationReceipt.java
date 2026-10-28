package com.bkanent.knowledge.api;

public record KnowledgeMutationReceipt(String requestId, String knowledgeId, String state,
                                       String revision, long version, String receiptStatus) { }
