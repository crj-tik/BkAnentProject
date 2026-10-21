package com.bkanent.common.memory;

import java.io.Serializable;

/** Subject and dimension are registry identities to be verified by memory, not model-created authority. */
public record PreferenceBlockKey(String ownerId, String subjectRef, MemoryScope scope, String dimensionId)
        implements Serializable {
    private static final long serialVersionUID = 1L;
    public PreferenceBlockKey {
        ownerId = MemoryContract.id(ownerId, MemoryException.Field.IDENTITY);
        subjectRef = MemoryContract.id(subjectRef, MemoryException.Field.PREFERENCE);
        MemoryContract.required(scope, MemoryException.Field.SCOPE);
        dimensionId = MemoryContract.id(dimensionId, MemoryException.Field.PREFERENCE);
    }

    /** Stable persisted identity. */
    public String identityHash() {
        // Persisted block IDs are already hashes of this record. Keep that exact wire/storage identity.
        return MemoryCanonicalHash.contentHash(this);
    }
}
