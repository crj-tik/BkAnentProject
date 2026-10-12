package com.bkanent.common.rag;

import com.bkanent.common.model.ListingDTO;
import java.io.Serializable;
import java.util.List;

public record RagListingMatch(ListingDTO listing, Double keywordScore, Double vectorScore, Double rerankScore,
                              Double relevanceScore, List<RagRecallMode> sources, RagCandidate candidate) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagListingMatch { sources = RagContract.list(sources); }

    @Override
    public String toString() { return "RagListingMatch[REDACTED]"; }
}
