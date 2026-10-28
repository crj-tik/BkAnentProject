package com.bkanent.knowledge.generation;

import com.bkanent.knowledge.source.InterviewSourceSnapshot;

import java.util.List;

@FunctionalInterface
public interface KnowledgeCandidateGenerator {
    KnowledgeCandidate generate(List<InterviewSourceSnapshot> sources);
}
