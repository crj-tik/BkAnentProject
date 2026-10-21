package com.bkanent.common.memory;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/** Search results contain only current SQL projection text, never raw RAG candidate bodies. */
public record MemoryCollaborationSearchResponse(List<MemoryCollaborationMatch> matches, Instant asOf,
        int utf8Bytes, boolean budgetLimited) implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int MAX_MATCHES = MemoryCollaborationSearchRequest.MAX_LIMIT;
    public static final int MAX_UTF8_BYTES = 24576;

    public MemoryCollaborationSearchResponse {
        matches = MemoryContract.list(matches, MemoryException.Field.EVIDENCE);
        MemoryContract.require(matches.size() <= MAX_MATCHES, MemoryException.Field.PAGE);
        MemoryContract.required(asOf, MemoryException.Field.SCORE);
        MemoryContract.require(utf8Bytes >= 0 && utf8Bytes <= MAX_UTF8_BYTES, MemoryException.Field.PAGE);
        MemoryContract.require(matches.stream().map(match -> match.exportRef().exportId() + ":"
                + match.exportRef().version().revision()).distinct().count() == matches.size(), MemoryException.Field.EVIDENCE);
        int actualBytes = matches.stream().mapToInt(match -> match.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();
        MemoryContract.require(actualBytes == utf8Bytes, MemoryException.Field.PAGE);
    }

    @Override public String toString() { return "MemoryCollaborationSearchResponse[REDACTED]"; }
}
