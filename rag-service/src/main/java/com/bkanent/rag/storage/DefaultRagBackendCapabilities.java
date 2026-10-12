package com.bkanent.rag.storage;

import org.springframework.stereotype.Component;

@Component
public final class DefaultRagBackendCapabilities implements RagBackendCapabilities {
    private final SpringAiMilvusDocumentStore vector;
    private final ElasticsearchProjectionStore keyword;
    public DefaultRagBackendCapabilities(SpringAiMilvusDocumentStore vector, ElasticsearchProjectionStore keyword) {
        this.vector = vector;
        this.keyword = keyword;
    }
    @Override public void inspectSchema(RagSpaceDefinition space) {
        keyword.inspectSchema(space);
        vector.verifySchema(space);
    }
    @Override public void provision(RagSpaceDefinition space) {
        keyword.provision(space);
        vector.provision(space.spaceId());
    }
}
