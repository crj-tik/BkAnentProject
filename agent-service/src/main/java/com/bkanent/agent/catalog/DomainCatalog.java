package com.bkanent.agent.catalog;

import com.bkanent.common.agent.AgentCard;

import java.util.List;
import java.util.Set;

/** Read-only domain diagnostics derived from the current Agent registry snapshot. */
public interface DomainCatalog {

    String SOURCE_REGISTRY = "REGISTRY";

    Set<String> domains();

    default boolean contains(String domain) {
        return domain != null && domains().contains(domain);
    }

    List<AgentCard> cards();

    CatalogSnapshot snapshot();

    record CatalogSnapshot(List<String> domains, List<AgentCard> cards, String vocabularySource) {
    }
}
