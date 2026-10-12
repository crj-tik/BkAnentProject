package com.bkanent.rag.index;

import java.util.Optional;

/** Storage-owned execution port. Implementations enforce transport deadlines and managed targets. */
public interface IndexSink {
    Backend backend();

    void upsert(IndexDocument document);

    void delete(IndexDocument tombstone);

    /** Read the actual stored version/hash, never the writer's cached acknowledgement. */
    Optional<StoredRevision> inspect(String spaceId, String documentId);

    enum Backend { KEYWORD, VECTOR }

    record StoredRevision(long revision, String contentHash, boolean tombstone) { }
}
