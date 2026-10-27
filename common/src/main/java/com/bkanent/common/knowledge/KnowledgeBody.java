package com.bkanent.common.knowledge;

import java.util.List;

public record KnowledgeBody(String problem, String scene, List<String> preconditions,
                            List<String> actions, List<String> observedResults,
                            List<String> applicability, List<String> unverifiedItems,
                            String evidenceLevel) {
    public KnowledgeBody {
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        actions = actions == null ? List.of() : List.copyOf(actions);
        observedResults = observedResults == null ? List.of() : List.copyOf(observedResults);
        applicability = applicability == null ? List.of() : List.copyOf(applicability);
        unverifiedItems = unverifiedItems == null ? List.of() : List.copyOf(unverifiedItems);
    }
}
