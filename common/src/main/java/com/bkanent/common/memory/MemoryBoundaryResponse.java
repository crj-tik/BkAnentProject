package com.bkanent.common.memory;

import java.io.Serializable;

/** Current version is data, not a reusable permission proof. Revalidate at every protected boundary. */
public record MemoryBoundaryResponse(MemoryConversationVersion version, boolean unchanged) implements Serializable { }
