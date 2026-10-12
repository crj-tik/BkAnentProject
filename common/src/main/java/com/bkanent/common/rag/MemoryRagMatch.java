package com.bkanent.common.rag;

import com.bkanent.common.agent.SystemConstraintRecord;
import com.bkanent.common.agent.UserPreferenceRecord;
import java.io.Serializable;

public record MemoryRagMatch(RagCandidate candidate, UserPreferenceRecord preference,
                             SystemConstraintRecord constraint, double domainScore) implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryRagMatch { RagContract.require(Double.isFinite(domainScore), "domainScore"); }

    @Override
    public String toString() { return "MemoryRagMatch[REDACTED]"; }
}
