package com.bkanent.common.memory;

/** Marker for the closed v2 request family; the envelope is excluded from body hashing. */
public interface MemoryV2Request {
    MemoryRequestEnvelope envelope();
    MemoryContextKey contextKey();
}
