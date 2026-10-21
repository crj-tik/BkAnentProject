package com.bkanent.memory.runtime;

import com.bkanent.common.memory.*;
import java.util.*;

/** Freezes safe, durable coverage. Model candidate generation and local run adoption remain separate steps. */
public final class MemoryCompactionCoordinator {
    public enum Trigger { TOKEN_PRESSURE, CONFIRMED_CLOSURE }
    public record Input(MemoryWorkingSetResponse snapshot, long through, List<MemoryMessage> messages,
                        List<MemorySourceDependency> dependencies, List<MemoryCoveredRange> toolGroups, Trigger trigger) { }
    @FunctionalInterface public interface CandidateGenerator { List<MemorySummary.Item> generate(Input input); }
    public record Candidate(List<MemorySummary.Item> items, List<MemoryEntityAnchor> entities, List<MemoryToolResultAnchor> toolResults) { }
    @FunctionalInterface public interface StructuredCandidateGenerator { Candidate generate(Input input); }
    public static final String ESTIMATOR_VERSION = "utf16-ceil-div4-v1-coarse";

    public int estimate(String text) { return (int) Math.min(Integer.MAX_VALUE, ((long) text.length() + 3) / 4); }

    public boolean compact(MemoryWorkingSession session, int recentMessages, Trigger trigger,
                           MemoryMatter.ClosureEvidence closure, CandidateGenerator generator, Runnable assertRunOwnership) {
        return compactStructured(session, recentMessages, trigger, closure,
                input -> new Candidate(generator.generate(input), List.of(), List.of()), assertRunOwnership);
    }

    public boolean compactStructured(MemoryWorkingSession session, int recentMessages, Trigger trigger,
                                     MemoryMatter.ClosureEvidence closure, StructuredCandidateGenerator generator, Runnable assertRunOwnership) {
        if (recentMessages < 1 || recentMessages > 500) throw new MemoryException(MemoryErrorCode.INVALID_ARGUMENT);
        session.check();
        var snapshot = session.snapshot();
        MemoryMatter closedMatter = null;
        if (trigger == Trigger.CONFIRMED_CLOSURE) {
            if (closure == null || !MemoryWorkingSession.dependencies(snapshot).containsAll(closure.evidence())) throw new MemoryException(MemoryErrorCode.FORBIDDEN);
            // A task's completion alone cannot prove a whole matter closed. Only explicit governed confirmation enters here.
            if (closure.kind() != MemoryMatter.ClosureKind.USER_CONFIRMATION) throw new MemoryException(MemoryErrorCode.FORBIDDEN);
            var matches = snapshot.matters().stream().filter(m -> m.state() == MemoryMatter.State.CLOSED && closure.equals(m.closure())).toList();
            if (matches.size() != 1) throw new MemoryException(MemoryErrorCode.FORBIDDEN);
            closedMatter = matches.get(0);
        }
        var tail = snapshot.messages();
        if (tail.size() <= recentMessages) return false;
        long through = tail.get(tail.size() - recentMessages - 1).sequence();
        if (closedMatter != null) {
            for (var message : tail) {
                boolean belongs = closedMatter.matterId().equals(message.association().matterId());
                boolean explicitStart = message.matterIntent() != null && message.matterIntent().action() == MemoryMatterIntent.Action.START
                        && closedMatter.dependencies().contains(new MemorySourceDependency(message.source(), null));
                if (!belongs && !explicitStart) { through = Math.min(through, message.sequence() - 1); break; }
            }
        }
        Map<String,MemoryMessage> calls = new LinkedHashMap<>();
        Map<MemoryMessage,List<MemoryMessage>> responses = new LinkedHashMap<>();
        Set<MemoryMessage> protectedGroups = new HashSet<>();
        for (var message : tail) {
            for (var call : message.toolCalls()) {
                if (calls.putIfAbsent(call.callId(), message) != null) return false;
                responses.computeIfAbsent(message, ignored -> new ArrayList<>());
            }
            for (var response : message.toolResponses()) {
                var assistant = calls.remove(response.callId());
                if (assistant == null) return false;
                if (!responses.get(assistant).contains(message)) responses.get(assistant).add(message);
                if (Set.of(MemoryToolResponse.ExecutionState.ACCEPTED, MemoryToolResponse.ExecutionState.WAITING_APPROVAL,
                        MemoryToolResponse.ExecutionState.OUTCOME_UNKNOWN).contains(response.state())) protectedGroups.add(assistant);
            }
        }
        protectedGroups.addAll(calls.values());
        for (var assistant : protectedGroups) through = Math.min(through, assistant.sequence() - 1);
        List<MemoryCoveredRange> groups = new ArrayList<>();
        if (!snapshot.summaries().isEmpty()) groups.addAll(snapshot.summaries().get(0).toolGroupRanges());
        for (var entry : responses.entrySet()) {
            if (protectedGroups.contains(entry.getKey())) continue;
            MemoryCoveredRange range;
            try { range = new MemoryToolGroup(entry.getKey(), entry.getValue()).range(); }
            catch (MemoryException exception) { return false; }
            if (range.fromSequence() <= through && range.throughSequence() > through) through = range.fromSequence() - 1;
        }
        long cut = through;
        var prefix = tail.stream().filter(m -> m.sequence() <= cut).toList();
        if (prefix.isEmpty()) return false;
        for (var entry : responses.entrySet()) {
            if (!protectedGroups.contains(entry.getKey())) {
                var range = new MemoryToolGroup(entry.getKey(), entry.getValue()).range();
                if (range.throughSequence() <= cut) groups.add(range);
            }
        }
        long previous = snapshot.summaries().isEmpty() ? 0 : snapshot.summaries().get(0).coveredRange().throughSequence();
        for (var message : prefix) if (message.sequence() != ++previous) return false;
        Set<MemorySourceDependency> dependencies = new LinkedHashSet<>();
        snapshot.summaries().forEach(s -> dependencies.addAll(s.dependencies()));
        prefix.forEach(m -> dependencies.add(new MemorySourceDependency(m.source(), null)));
        var input = new Input(snapshot, cut, prefix, List.copyOf(dependencies), List.copyOf(groups), trigger);
        Candidate candidate = generator.generate(input);
        if (candidate == null) throw new MemoryException(MemoryErrorCode.INVALID_EXTRACTION);
        List<MemorySummary.Item> items = candidate.items();
        if (items == null || items.isEmpty() || items.stream().anyMatch(i -> !dependencies.containsAll(i.evidence()))) throw new MemoryException(MemoryErrorCode.INVALID_EXTRACTION);
        String id = "s-" + MemoryCanonicalHash.contentHash(List.of(snapshot.contextKey(), snapshot.version().revision()));
        var association = new MemoryAssociation(null, session.authority().runId(), null, null);
        List<MemoryToolResultAnchor> results = new ArrayList<>();
        snapshot.summaries().forEach(s -> results.addAll(s.toolResults()));
        if (candidate.toolResults() != null) candidate.toolResults().forEach(result -> { if (!results.contains(result)) results.add(result); });
        var draft = new MemorySummary(id, snapshot.contextKey(), association, new MemoryRevision(1, "0".repeat(64), "summary.v2.1"),
                snapshot.version().revision(), null, new MemoryCoveredRange(1, cut), groups, items, candidate.entities(), results, List.copyOf(dependencies));
        var summary = new MemorySummary(id, draft.contextKey(), association, new MemoryRevision(1, MemoryCanonicalHash.summaryHash(draft), draft.version().schemaVersion()),
                draft.expectedConversationRevision(), null, draft.coveredRange(), groups, items, draft.entities(), results, draft.dependencies());
        session.commit(summary, assertRunOwnership);
        return true;
    }
}
