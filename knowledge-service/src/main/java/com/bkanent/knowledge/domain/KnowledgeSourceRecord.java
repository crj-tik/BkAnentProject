package com.bkanent.knowledge.domain;

import com.bkanent.knowledge.source.SourceBinding;

public record KnowledgeSourceRecord(SourceBinding binding, String quoteHash, Integer quoteStart, Integer quoteEnd) { }
