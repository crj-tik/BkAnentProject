package com.bkanent.common.memory;

import java.io.Serializable;

public record MemoryWriterResponse(MemoryWriteAuthority authority, long expiresAtEpochMilli,
                                    MemoryConversationVersion version) implements Serializable { }
