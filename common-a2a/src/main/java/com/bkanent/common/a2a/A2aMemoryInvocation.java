package com.bkanent.common.a2a;

import com.bkanent.common.memory.MemoryContextKey;
import com.bkanent.memory.runtime.MemoryWorkingSession;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/** Per-task transient hook state. It deliberately contains no credential or lease. */
public final class A2aMemoryInvocation {
    private final String taskId;
    private final String contextId;
    private volatile String memoryStatus = "NOT_APPLIED";
    private volatile String archiveStatus = "NOT_APPLIED";
    private volatile String compactionStatus = "NOT_APPLIED";
    private volatile MemoryContextKey contextKey;
    private volatile MemoryWorkingSession session;
    private volatile int historyCount;
    private volatile int insertionIndex;
    private volatile int currentInputCount;
    private volatile boolean privateMaterialLoaded;
    private volatile boolean prepared;

    A2aMemoryInvocation(String taskId, String contextId, boolean enabled) {
        this.taskId = taskId;
        this.contextId = contextId == null ? null : "a2a-input-context-" + com.bkanent.common.memory.MemoryCanonicalHash.contentHash(contextId);
        this.memoryStatus = enabled ? "NOT_APPLIED" : "DISABLED";
        this.archiveStatus = enabled ? "NOT_APPLIED" : "DISABLED";
    }

    public String taskId() { return taskId; }
    public String contextId() { return contextId; }
    public String memoryStatus() { return memoryStatus; }
    public String archiveStatus() { return archiveStatus; }
    public String compactionStatus() { return compactionStatus; }

    MemoryContextKey contextKey() { return contextKey; }
    void contextKey(MemoryContextKey value) { contextKey = value; }
    MemoryWorkingSession session() { return session; }
    void session(MemoryWorkingSession value) { session = value; }
    int historyCount() { return historyCount; }
    void historyCount(int value) { historyCount = value; }
    int insertionIndex() { return insertionIndex; }
    void insertionIndex(int value) { insertionIndex = value; }
    int currentInputCount() { return currentInputCount; }
    void currentInputCount(int value) { currentInputCount = value; }
    boolean privateMaterialLoaded() { return privateMaterialLoaded; }
    void privateMaterialLoaded(boolean value) { privateMaterialLoaded = value; }
    boolean prepared() { return prepared; }
    void prepared(boolean value) { prepared = value; }
    void memoryStatus(String value) { memoryStatus = value; }
    void archiveStatus(String value) { archiveStatus = value; }
    void compactionStatus(String value) { compactionStatus = value; }

    int currentInputStart() { return insertionIndex + historyCount; }

    static int firstNonSystem(List<Message> messages) {
        int index = 0;
        while (index < messages.size() && messages.get(index) instanceof org.springframework.ai.chat.messages.SystemMessage) index++;
        return index;
    }

    @Override public String toString() { return "A2aMemoryInvocation[REDACTED]"; }
}
