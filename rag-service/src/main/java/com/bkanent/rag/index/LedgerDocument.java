package com.bkanent.rag.index;

import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagIndexState;
import java.time.Instant;

/** Mutable only inside the repository's document-row-lock transaction. */
final class LedgerDocument {
    String spaceId;
    String documentId;
    long revision;
    String hash;
    String payloadHash;
    String eventId;
    String schemaVersion;
    boolean tombstone;
    RagIndexState state;
    long keywordRevision;
    long vectorRevision;
    int keywordAttempts;
    int vectorAttempts;
    RagErrorCode keywordError;
    RagErrorCode vectorError;
    Instant keywordRetry;
    Instant vectorRetry;
    Instant reconcileAt;
}
