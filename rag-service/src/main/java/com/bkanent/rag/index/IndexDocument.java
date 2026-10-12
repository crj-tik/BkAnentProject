package com.bkanent.rag.index;

import com.bkanent.common.rag.RagDocument;
import com.bkanent.common.rag.RagIndexEvent;
import com.bkanent.common.rag.RagIndexOperation;

/** Durable, credential-free projection supplied to storage implementations. */
public record IndexDocument(RagIndexEvent event, String canonicalPayload) {
    public String eventId() { return event.eventId(); }
    public String spaceId() { return event.spaceId(); }
    public String documentId() { return event.documentId(); }
    public long revision() { return event.revision(); }
    public String schemaVersion() { return event.schemaVersion(); }
    public RagIndexOperation operation() { return event.operation(); }
    public String contentHash() { return event.contentHash(); }
    public RagDocument document() { return event.document(); }

    @Override
    public String toString() {
        return "IndexDocument[revision=" + revision() + ", operation=" + operation() + "]";
    }
}
