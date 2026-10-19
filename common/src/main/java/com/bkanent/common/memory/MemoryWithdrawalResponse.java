package com.bkanent.common.memory;

import java.io.Serializable;

/** Accepted suppression is immediate. DELETION_PENDING never claims every projection was erased. */
public record MemoryWithdrawalResponse(String sourceEventId, MemoryState state,
                                        MemoryConversationVersion version) implements Serializable { }
