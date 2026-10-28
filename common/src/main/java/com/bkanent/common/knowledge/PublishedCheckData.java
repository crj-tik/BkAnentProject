package com.bkanent.common.knowledge;

import java.util.List;

public record PublishedCheckData(String contractVersion, List<PublishedCheckItem> items) {
    public PublishedCheckData {
        KnowledgeContract.requiredContractVersion(contractVersion);
        items = items == null ? List.of() : List.copyOf(items);
    }
}
