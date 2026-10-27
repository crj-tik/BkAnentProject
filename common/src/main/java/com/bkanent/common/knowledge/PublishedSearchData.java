package com.bkanent.common.knowledge;

import java.util.List;

public record PublishedSearchData(String contractVersion, List<PublishedSearchItem> items, String nextCursor) {
    public PublishedSearchData {
        KnowledgeContract.requiredContractVersion(contractVersion);
        items = items == null ? List.of() : List.copyOf(items);
    }
}
