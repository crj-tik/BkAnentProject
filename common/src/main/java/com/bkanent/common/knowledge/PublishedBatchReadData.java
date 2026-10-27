package com.bkanent.common.knowledge;

import java.util.List;

public record PublishedBatchReadData(String contractVersion, List<PublishedBatchReadItem> items) {
    public PublishedBatchReadData {
        KnowledgeContract.requiredContractVersion(contractVersion);
        items = items == null ? List.of() : List.copyOf(items);
    }
}
