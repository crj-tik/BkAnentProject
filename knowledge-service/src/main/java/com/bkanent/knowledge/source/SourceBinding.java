package com.bkanent.knowledge.source;

import java.util.Objects;

/** Immutable pointer to the exact sanitized Interview snapshot used by a Knowledge revision. */
public record SourceBinding(String assetId, String snapshotHash, String contentHash,
                            int contentRevision, int sanitizationRevision,
                            int publicationRevision, String dataMode) {
    public SourceBinding {
        Objects.requireNonNull(assetId);
        Objects.requireNonNull(snapshotHash);
        Objects.requireNonNull(contentHash);
        Objects.requireNonNull(dataMode);
    }

    public boolean sameSnapshot(SourceBinding other) {
        return other != null && assetId.equals(other.assetId) && snapshotHash.equals(other.snapshotHash)
                && contentHash.equals(other.contentHash) && contentRevision == other.contentRevision
                && sanitizationRevision == other.sanitizationRevision
                && publicationRevision == other.publicationRevision && dataMode.equals(other.dataMode);
    }
}
