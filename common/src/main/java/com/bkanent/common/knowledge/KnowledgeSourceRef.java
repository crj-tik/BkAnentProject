package com.bkanent.common.knowledge;

public record KnowledgeSourceRef(String sourceSystem, String sourceId, String snapshotHash,
                                 String contentHash, int contentRevision, int sanitizationRevision,
                                 int publicationRevision, String dataMode, String quoteHash,
                                 Integer quoteStart, Integer quoteEnd) { }
