package com.bkanent.memory.runtime;

import com.bkanent.common.memory.*;
import java.util.*;

/** One fenced run. Every reuse/output is reauthorized; source denial always evicts material. */
public final class MemoryWorkingSession implements AutoCloseable {
    private final MemoryRpcClient client;
    private final MemoryWorkingSetCache cache;
    private final MemoryContextKey context;
    private final MemoryWriteAuthority writer;
    private MemoryWorkingSetResponse snapshot;

    public MemoryWorkingSession(MemoryRpcClient client, MemoryWorkingSetCache cache, MemoryContextKey context, String runId) {
        this.client = client; this.cache = cache; this.context = context;
        writer = client.writer(context, MemoryWriterRequest.Action.ACQUIRE, runId, 0).authority();
        try {
            snapshot = cache.get(context);
            if (snapshot == null) { snapshot = client.read(context); cache.put(snapshot); }
            else check();
        } catch (RuntimeException exception) { cache.remove(context); release(); throw exception; }
    }
    public MemoryWorkingSetResponse snapshot() { return snapshot; }
    public MemoryWriteAuthority authority() { return writer; }
    public void check() {
        try {
            var current = client.check(snapshot, dependencies(snapshot));
            if (!current.unchanged()) { snapshot = client.read(context); cache.put(snapshot); }
        } catch (RuntimeException exception) { cache.remove(context); throw exception; }
    }
    public void renew() { client.writer(context, MemoryWriterRequest.Action.RENEW, writer.runId(), writer.fence()); }
    public MemoryDeliveryResponse deliver(String permit, List<MemoryMessage> messages) {
        var receipt = client.deliver(context, messages.get(0).association(), permit, messages);
        if (!permit.equals(receipt.permitId()) || receipt.results().size() != messages.size()) throw new MemoryException(MemoryErrorCode.ARCHIVE_NOT_CONFIRMED);
        for (int i = 0; i < messages.size(); i++) {
            if (!receipt.results().get(i).accepted() || receipt.results().get(i).sequence() != messages.get(i).sequence())
                throw new MemoryException(MemoryErrorCode.ARCHIVE_NOT_CONFIRMED);
        }
        var merged = new TreeMap<Long,MemoryMessage>();
        snapshot.messages().forEach(m -> merged.put(m.sequence(), m));
        long covered = snapshot.summaries().isEmpty() ? 0 : snapshot.summaries().get(0).coveredRange().throughSequence();
        messages.stream().filter(m -> m.sequence() > covered).forEach(m -> merged.put(m.sequence(), m));
        snapshot = new MemoryWorkingSetResponse(context, receipt.version(), List.copyOf(merged.values()), snapshot.summaries(), snapshot.matters());
        try { cache.put(snapshot); }
        catch (MemoryException exception) {
            if (exception.code() != MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED) throw exception;
            cache.remove(context); // durable receipt remains true; caller applies the hard input budget separately
        }
        return receipt;
    }
    public String permit(List<MemoryMessage> messages) {
        renew();
        var response = client.permit(context, messages, writer);
        if (response.acceptances().size() != messages.size() || response.acceptances().stream().anyMatch(a -> !a.accepted())) {
            throw new MemoryException(MemoryErrorCode.ARCHIVE_NOT_CONFIRMED);
        }
        return response.permitId();
    }
    public MemorySummaryResponse commit(MemorySummary candidate, Runnable assertRunOwnership) {
        try {
            assertRunOwnership.run(); renew(); check();
            if (snapshot.version().revision() != candidate.expectedConversationRevision()) throw new MemoryException(MemoryErrorCode.VERSION_CONFLICT);
            var receipt = client.summary(candidate, writer);
            if (!receipt.accepted()) throw new MemoryException(MemoryErrorCode.ARCHIVE_NOT_CONFIRMED);
            assertRunOwnership.run(); renew();
            snapshot = new MemoryWorkingSetResponse(context, receipt.version(), snapshot.messages().stream()
                    .filter(m -> m.sequence() > candidate.coveredRange().throughSequence()).toList(), List.of(candidate), snapshot.matters());
            cache.put(snapshot);
            return receipt;
        } catch (RuntimeException exception) { cache.remove(context); throw exception; }
    }
    public static List<MemorySourceDependency> dependencies(MemoryWorkingSetResponse value) {
        Set<MemorySourceDependency> result = new LinkedHashSet<>();
        value.messages().forEach(m -> result.add(new MemorySourceDependency(m.source(), null)));
        value.summaries().forEach(s -> result.addAll(s.dependencies()));
        value.matters().forEach(m -> result.addAll(m.dependencies()));
        return List.copyOf(result);
    }
    private void release() { try { client.writer(context, MemoryWriterRequest.Action.RELEASE, writer.runId(), writer.fence()); } catch (RuntimeException ignored) { } }
    public void close() { release(); }
}
