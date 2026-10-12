package com.bkanent.rag.storage;

/** Storage-owned management operations. No initialization is implied by reads or search. */
public interface RagBackendCapabilities {
    void inspectSchema(RagSpaceDefinition space);
    void provision(RagSpaceDefinition space);
}
