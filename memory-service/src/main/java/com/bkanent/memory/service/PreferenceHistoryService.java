package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.memory.config.MemoryPreferenceProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.bkanent.memory.service.MemoryArchiveStore.*;

/** Versioned self/private preference history. No model invocation, background-user impersonation or legacy upsert. */
@Service
public class PreferenceHistoryService {
    private final MemoryArchiveStore store;
    private final MemoryV2RequestSecurity security;
    private final MemorySourceAuthorizer sources;
    private final MemoryPreferenceProperties properties;
    private final TransactionTemplate tx;
    private final PreferenceStateReducer reducer = new PreferenceStateReducer();
    private final PreferenceSelectionPolicy selector = new PreferenceSelectionPolicy();
    private static final int MAX_HISTORY = 10000;
    private static final int MAX_HISTORY_CHARS = 8 * 1024 * 1024;
    private record View(MemoryPreferenceDetailResponse response, List<MemorySourceDependency> sources) { }

    public PreferenceHistoryService(MemoryArchiveStore store, MemoryV2RequestSecurity security, MemorySourceAuthorizer sources,
                                    MemoryPreferenceProperties properties, PlatformTransactionManager manager) {
        this.store = store; this.security = security; this.sources = sources; this.properties = properties;
        tx = new TransactionTemplate(manager); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(10);
    }

    public MemoryPreferenceWriteResponse submit(MemoryPreferenceWriteRequest request, MemoryRequestProof proof) {
        security.authorize(request, proof, MemoryV2Contract.OPERATION_PREFERENCE_WRITE);
        return transaction(() -> {
            var result = append(request.contextKey(), request.candidate(), request.envelope().caller(), request.expectedRevision());
            MemoryV2RequestSecurity.requireUnexpired(proof);
            return result;
        });
    }

    /** Reads only owner/domain/agent-private current values and pins each selection for output-time reauthorization. */
    public MemoryPreferenceAssemblyResponse assemble(MemoryPreferenceAssemblyRequest request, MemoryRequestProof proof) {
        long owner = security.authorize(request, proof, MemoryV2Contract.OPERATION_ASSEMBLE_PREFERENCES);
        Instant asOf = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var snapshot = transaction(() -> assembleAt(request, asOf));
        security.revalidate(request, proof, owner, MemoryV2Contract.OPERATION_ASSEMBLE_PREFERENCES);
        return transaction(() -> {
            var current = assembleAt(request, asOf);
            require(snapshot.equals(current), MemoryErrorCode.VERSION_CONFLICT);
            MemoryV2RequestSecurity.requireUnexpired(proof);
            store.encode(current);
            return current;
        });
    }

    /** Rechecks selected block revision, source grants and current matter-effective value without accepting caller content. */
    boolean selectionsCurrent(MemoryContextKey context, List<MemoryPreferenceSelectionRef> selections) {
        return transaction(() -> {
            for (var selection : selections) {
                var block = store.one("SELECT owner_id,domain_id,agent_id,subject_ref,dimension_id,revision,authorization_revision,state "
                        + "FROM memory_preference_block WHERE block_id=?", selection.blockId());
                require(block != null, MemoryErrorCode.VERSION_CONFLICT);
                require(context.ownerId().equals(block.get("owner_id")) && context.domainId().equals(block.get("domain_id"))
                        && context.agentId().equals(block.get("agent_id")) && context.ownerId().equals(block.get("subject_ref")), MemoryErrorCode.FORBIDDEN);
                if (!"ACTIVE".equals(block.get("state")) || number(block, "revision") != selection.revision()
                        || number(block, "authorization_revision") != selection.authorizationRevision()) return false;
                var matter = store.one("SELECT content_revision,state FROM memory_matter WHERE context_id=? AND matter_id=?", key(context), selection.matterId());
                if (matter == null || !Set.of("ACTIVE", "REOPENED").contains(matter.get("state"))
                        || number(matter, "content_revision") != selection.matterRevision()) return false;
                var version = store.one("SELECT content_hash FROM memory_preference_revision WHERE block_id=? AND revision=?",
                        selection.blockId(), selection.revision());
                if (version == null || !selection.contentHash().equals(version.get("content_hash"))) return false;
                List<MemorySourceDependency> sources = dependencies(selection.blockId(), selection.revision());
                checkSources(context, sources); // checks current source/grant metadata before reading derived bodies
                var snapshotRow = store.one("SELECT snapshot_json FROM memory_preference_revision WHERE block_id=? AND revision=?",
                        selection.blockId(), selection.revision());
                require(snapshotRow != null && snapshotRow.get("snapshot_json") != null, MemoryErrorCode.WITHDRAWN);
                var snapshot = store.decode((String) snapshotRow.get("snapshot_json"), PreferenceBlock.class);
                require(snapshot.blockId().equals(selection.blockId()) && snapshot.version().revision() == selection.revision()
                        && snapshot.version().contentHash().equals(selection.contentHash()) && blockHash(snapshot).equals(selection.contentHash()),
                        MemoryErrorCode.MEMORY_UNAVAILABLE);
                var history = transitions(selection.blockId(), selection.revision());
                var effective = reducer.reduce(history, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), selection.matterId());
                if (effective.status() != PreferenceCurrentState.Status.EFFECTIVE
                        || !MemoryCanonicalHash.contentHash(effective).equals(selection.effectiveStateHash())) return false;
            }
            return true;
        });
    }

    private MemoryPreferenceAssemblyResponse assembleAt(MemoryPreferenceAssemblyRequest request, Instant asOf) {
        MemoryContextKey context = request.contextKey();
        store.conversation(context);
        var matterHeader = store.one("SELECT content_revision,state FROM memory_matter WHERE context_id=? AND matter_id=?",
                key(context), request.matterId());
        require(matterHeader != null && Set.of("ACTIVE", "REOPENED").contains(matterHeader.get("state")), MemoryErrorCode.NOT_FOUND);
        long matterRevision = number(matterHeader, "content_revision");
        var matterDependencies = dependenciesForTarget(context, "MATTER", request.matterId(), matterRevision);
        sources.requireCurrentMetadata(context, matterDependencies);
        var matterBody = store.one("SELECT payload_json FROM memory_matter WHERE context_id=? AND matter_id=? AND content_revision=? AND state=?",
                key(context), request.matterId(), matterRevision, matterHeader.get("state"));
        require(matterBody != null && matterBody.get("payload_json") != null, MemoryErrorCode.VERSION_CONFLICT);
        var matter = store.decode((String) matterBody.get("payload_json"), MemoryMatter.class);
        require(matter.contextKey().equals(context) && matter.matterId().equals(request.matterId())
                && matter.version().revision() == matterRevision && matter.state().name().equals(matterHeader.get("state"))
                && MemoryCanonicalHash.matterHash(matter).equals(matter.version().contentHash())
                && new HashSet<>(matterDependencies).equals(new HashSet<>(matter.dependencies())), MemoryErrorCode.MEMORY_UNAVAILABLE);

        // The block scope and subject are derived from the authenticated context. No request/model field can widen them.
        var headers = store.jdbc.queryForList("SELECT block_id,revision,authorization_revision,subject_ref,dimension_id,state "
                + "FROM memory_preference_block WHERE owner_id=? AND domain_id=? AND agent_id=? AND subject_ref=? AND state='ACTIVE' "
                + "ORDER BY block_id LIMIT ?", context.ownerId(), context.domainId(), context.agentId(), context.ownerId(),
                Math.max(1, properties.getDimensions().size() + 1));
        require(headers.size() <= properties.getDimensions().size(), MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
        List<MemoryPreferenceMatch> candidates = new ArrayList<>();
        String objectiveHash = MemoryCanonicalHash.contentHash(matter.goal());
        for (var header : headers) {
            String dimension = (String) header.get("dimension_id");
            if (!properties.getDimensions().contains(dimension)) continue;
            var relevance = selector.relevance(matter.goal(), properties.aliasesFor(dimension));
            if (relevance.isEmpty()) continue;
            String blockId = (String) header.get("block_id"); long revision = number(header, "revision");
            long authorizationRevision = number(header, "authorization_revision");
            require(context.ownerId().equals(header.get("subject_ref")) && "ACTIVE".equals(header.get("state")), MemoryErrorCode.FORBIDDEN);
            PreferenceBlockKey expectedKey = new PreferenceBlockKey(context.ownerId(), context.ownerId(),
                    MemoryScope.privateScope(context.domainId(), context.agentId()), dimension);
            require(blockId.equals("p-" + expectedKey.identityHash()), MemoryErrorCode.FORBIDDEN);
            var dependencies = dependencies(blockId, revision);
            checkSources(context, dependencies);
            var snapshotRow = store.one("SELECT content_hash,snapshot_json FROM memory_preference_revision WHERE block_id=? AND revision=?",
                    blockId, revision);
            require(snapshotRow != null && snapshotRow.get("snapshot_json") != null, MemoryErrorCode.VERSION_CONFLICT);
            var block = store.decode((String) snapshotRow.get("snapshot_json"), PreferenceBlock.class);
            require(block.blockId().equals(blockId) && block.version().revision() == revision
                    && block.version().contentHash().equals(snapshotRow.get("content_hash"))
                    && blockHash(block).equals(snapshotRow.get("content_hash")) && block.key().equals(expectedKey), MemoryErrorCode.MEMORY_UNAVAILABLE);
            var history = transitions(blockId, revision);
            var effective = reducer.reduce(history, asOf, request.matterId());
            if (effective.status() != PreferenceCurrentState.Status.EFFECTIVE) continue;
            double timeWeight = selector.timeWeight(effective.lastObservedAt(), asOf, properties.getHalfLifeDays());
            var score = new MemorySelectionScore(relevance.getAsDouble(), effective.confidence(), effective.activation(),
                    timeWeight, asOf, PreferenceSelectionPolicy.VERSION);
            var ref = new MemoryPreferenceSelectionRef(blockId, revision, (String) snapshotRow.get("content_hash"),
                    authorizationRevision, request.matterId(), matterRevision, MemoryCanonicalHash.contentHash(effective));
            candidates.add(new MemoryPreferenceMatch(dimension, effective, score, ref));
        }
        candidates.sort(Comparator.comparingDouble((MemoryPreferenceMatch match) -> weighted(match.score())).reversed()
                .thenComparing(MemoryPreferenceMatch::dimensionId));
        List<MemoryPreferenceMatch> selected = new ArrayList<>();
        int usedTokens = 0; boolean budgetLimited = false;
        for (var candidate : candidates) {
            int tokens = selector.estimatedTokens(store.encode(candidate).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (tokens <= request.tokenBudget() - usedTokens) { selected.add(candidate); usedTokens += tokens; }
            else budgetLimited = true;
        }
        return new MemoryPreferenceAssemblyResponse(request.matterId(), matterRevision, objectiveHash, asOf,
                PreferenceSelectionPolicy.VERSION, selected, budgetLimited);
    }

    private static double weighted(MemorySelectionScore score) {
        return score.relevance() * score.confidence() * score.activation() * score.timeWeight();
    }

    private List<MemorySourceDependency> dependenciesForTarget(MemoryContextKey context, String kind, String target, long revision) {
        var rows = store.jdbc.query("SELECT dependency_json FROM memory_source_dependency WHERE target_context_id=? AND target_kind=? "
                        + "AND target_id=? AND target_revision=? LIMIT 501",
                (rs, n) -> store.decode(rs.getString(1), MemorySourceDependency.class), key(context), kind, target, revision);
        require(!rows.isEmpty() && rows.size() <= 500, MemoryErrorCode.MEMORY_UNAVAILABLE);
        return rows;
    }

    /** Package-private: only the leased job consumer calls this inside its exact-evidence transaction. */
    MemoryPreferenceWriteResponse append(MemoryContextKey context, MemoryPreferenceAssessment candidate, String sourceCaller, Long expectedRevision) {
            requireTransaction();
            require(properties.getDimensions().contains(candidate.dimensionId()), MemoryErrorCode.INVALID_EXTRACTION);
            safe(candidate.value()); safeText(candidate.applicability());
            var blockKey = new PreferenceBlockKey(context.ownerId(), context.ownerId(), MemoryScope.privateScope(context.domainId(), context.agentId()), candidate.dimensionId());
            String hash = blockKey.identityHash(), id = "p-" + hash;
            sources.requireCurrentMetadata(context, List.of(candidate.source()));
            var source = candidate.source().source();
            var raw = store.one("SELECT payload_json,source_caller FROM memory_message WHERE context_id=? AND source_event_id=?", key(context), source.sourceEventId());
            require(raw != null && sourceCaller.equals(raw.get("source_caller")), MemoryErrorCode.FORBIDDEN);
            var message = store.decode((String) raw.get("payload_json"), MemoryMessage.class);
            require(message.role() == MemoryMessage.Role.USER && message.source().equals(source)
                    && MemoryCanonicalHash.messageHash(message).equals(source.version().contentHash()), MemoryErrorCode.INVALID_EXTRACTION);
            if (candidate.assessment() != PreferenceTransition.Assessment.PREFERENCE) return new MemoryPreferenceWriteResponse(id, 0, null, false);
            require(candidate.change() == PreferenceTransition.Change.WITHDRAWAL || candidate.value().type() != MemoryJsonValue.Type.NULL
                    && (candidate.value().type() != MemoryJsonValue.Type.STRING || !candidate.value().scalar().isBlank()), MemoryErrorCode.INVALID_EXTRACTION);
            var time = store.one("SELECT accepted_at FROM memory_message_observation WHERE context_id=? AND source_event_id=?", key(context), source.sourceEventId());
            require(time != null, MemoryErrorCode.MEMORY_UNAVAILABLE); // no invented time for older sources
            Instant occurred = utcInstant(time.get("accepted_at"));
            Instant observed = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            require(candidate.effectiveFrom() == null || candidate.effectiveFrom().getNano() % 1000 == 0, MemoryErrorCode.INVALID_ARGUMENT);
            require(candidate.effectiveUntil() == null || candidate.effectiveUntil().getNano() % 1000 == 0, MemoryErrorCode.INVALID_ARGUMENT);
            require(candidate.change() == PreferenceTransition.Change.TEMPORARY_EXCEPTION || candidate.matterId() == null && candidate.effectiveUntil() == null,
                    MemoryErrorCode.INVALID_EXTRACTION);
            require(candidate.change() == PreferenceTransition.Change.TEMPORARY_EXCEPTION || candidate.effectiveFrom() == null || !candidate.effectiveFrom().isAfter(observed),
                    MemoryErrorCode.INVALID_EXTRACTION);
            store.jdbc.update("INSERT INTO memory_preference_block(block_id,key_hash,owner_id,domain_id,agent_id,subject_ref,dimension_id) VALUES(?,?,?,?,?,?,?) "
                    + "ON DUPLICATE KEY UPDATE block_id=block_id", id, hash, context.ownerId(), context.domainId(), context.agentId(), context.ownerId(), candidate.dimensionId());
            var block = header(context, id);
            String candidateHash = MemoryCanonicalHash.contentHash(candidate);
            String transitionId = "t-" + MemoryCanonicalHash.contentHash(List.of(id, source.contextKey(), source.sourceEventId()));
            var existing = store.one("SELECT * FROM memory_preference_transition WHERE block_id=? AND transition_id=?", id, transitionId);
            if (existing != null) {
                require(candidateHash.equals(existing.get("candidate_hash")), MemoryErrorCode.VERSION_CONFLICT);
                return new MemoryPreferenceWriteResponse(id, number(existing, "introduced_revision"), transitionId, true);
            }
            require(expectedRevision == null || expectedRevision == number(block, "revision"), MemoryErrorCode.VERSION_CONFLICT);
            checkSources(context, dependencies(id, number(block, "revision")));
            var history = transitions(id, number(block, "revision"));
            for (String target : candidate.correctsTransitionIds()) {
                require(Set.of(PreferenceTransition.Change.LONG_TERM_CORRECTION, PreferenceTransition.Change.TEMPORARY_EXCEPTION,
                        PreferenceTransition.Change.CONFIRMATION, PreferenceTransition.Change.WITHDRAWAL).contains(candidate.change()), MemoryErrorCode.INVALID_EXTRACTION);
                var old = history.stream().filter(t -> t.transitionId().equals(target)).findFirst().orElseThrow(() -> new MemoryException(MemoryErrorCode.VERSION_CONFLICT));
                require(candidate.change() != PreferenceTransition.Change.CONFIRMATION || old.value().equals(candidate.value()) && old.polarity() == candidate.polarity(),
                        MemoryErrorCode.INVALID_EXTRACTION);
                var oldSource = old.evidence().get(0).source();
                require(!oldSource.contextKey().equals(source.contextKey()) || source.sourceSequence() > oldSource.sourceSequence(), MemoryErrorCode.VERSION_CONFLICT);
            }
            require(candidate.change() != PreferenceTransition.Change.WITHDRAWAL || !candidate.correctsTransitionIds().isEmpty(), MemoryErrorCode.INVALID_EXTRACTION);
            long revision = Math.addExact(number(block, "revision"), 1), ordinal = Math.addExact(number(block, "last_ordinal"), 1);
            var transition = new PreferenceTransition(transitionId, ordinal, revision, candidate.assessment(), candidate.change(), candidate.polarity(), candidate.value(),
                    candidate.applicability(), candidate.matterId(), occurred, observed, candidate.effectiveFrom() == null ? occurred : candidate.effectiveFrom(),
                    candidate.effectiveUntil(), candidate.correctsTransitionIds(), candidate.confidence(), candidate.extractionVersion(), List.of(candidate.source()));
            history.add(transition);
            require(history.size() <= MAX_HISTORY, MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
            var current = reducer.reduce(history, observed, null);
            var draft = new PreferenceBlock(id, blockKey, new MemoryRevision(revision, "0".repeat(64), "preference.v2.1"), MemoryState.ACTIVE, current,
                    new PreferenceHistoryCursor(id, revision, number(block, "authorization_revision"), 0));
            var snapshot = new PreferenceBlock(id, blockKey, new MemoryRevision(revision, blockHash(draft), draft.version().schemaVersion()), MemoryState.ACTIVE, current, draft.historyStart());
            store.jdbc.update("INSERT INTO memory_preference_transition(block_id,transition_id,history_ordinal,introduced_revision,source_context_id,source_event_id,candidate_hash,transition_hash,source_json,payload_json) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?)", id, transitionId, ordinal, revision, key(context), source.sourceEventId(), candidateHash, MemoryCanonicalHash.contentHash(transition), store.encode(candidate.source()), store.encode(transition));
            store.jdbc.update("INSERT INTO memory_preference_revision(block_id,revision,content_hash,snapshot_json) VALUES(?,?,?,?)", id, revision, snapshot.version().contentHash(), store.encode(snapshot));
            store.jdbc.update("UPDATE memory_preference_block SET revision=?,last_ordinal=? WHERE block_id=?", revision, ordinal, id);
            store.dependencies(key(context), "PREFERENCE", id, revision, List.of(candidate.source()));
            store.enqueue(key(context), MemoryCanonicalHash.contentHash(List.of(id, revision)), "INDEX_PREFERENCE");
            return new MemoryPreferenceWriteResponse(id, revision, transitionId, true);
    }

    MemoryPreferenceAssessment linkWithinConversation(MemoryPreferenceAssessment candidate) {
        requireTransaction();
        var change = candidate.change();
        if (change == PreferenceTransition.Change.OBSERVATION || change == PreferenceTransition.Change.UNRESOLVED_CONFLICT) return candidate;
        var source = candidate.source().source(); var context = source.contextKey();
        // The model never chooses another person's source or transition IDs. Only this conversation's older evidence can be linked.
        var rows = store.jdbc.queryForList("SELECT t.transition_id FROM memory_preference_transition t JOIN memory_preference_block b ON b.block_id=t.block_id "
                + "JOIN memory_message m ON m.context_id=t.source_context_id AND m.source_event_id=t.source_event_id "
                + "WHERE t.source_context_id=? AND b.owner_id=? AND b.domain_id=? AND b.agent_id=? AND b.dimension_id=? "
                + "AND m.sequence_no<? ORDER BY m.sequence_no DESC LIMIT 1", String.class, key(context), context.ownerId(), context.domainId(), context.agentId(), candidate.dimensionId(), source.sourceSequence());
        var targets = rows.isEmpty() ? List.<String>of() : List.of(rows.get(0));
        if (change == PreferenceTransition.Change.WITHDRAWAL && targets.isEmpty()) throw new MemoryException(MemoryErrorCode.INVALID_EXTRACTION);
        if (change == PreferenceTransition.Change.LONG_TERM_CORRECTION && targets.isEmpty()) change = PreferenceTransition.Change.UNRESOLVED_CONFLICT;
        return new MemoryPreferenceAssessment(candidate.dimensionId(), candidate.assessment(), change, candidate.polarity(), candidate.value(), candidate.applicability(),
                candidate.matterId(), candidate.effectiveFrom(), candidate.effectiveUntil(), targets, candidate.confidence(), candidate.extractionVersion(), candidate.source());
    }

    public MemoryPreferenceDetailResponse read(MemoryPreferenceDetailRequest request, MemoryRequestProof proof) {
        long owner = security.authorize(request, proof, MemoryV2Contract.OPERATION_PREFERENCE_DETAIL);
        var view = transaction(() -> detail(request));
        security.revalidate(request, proof, owner, MemoryV2Contract.OPERATION_PREFERENCE_DETAIL);
        return transaction(() -> {
            var current = header(request.contextKey(), request.blockId());
            require(number(current, "authorization_revision") == view.response().history().cursor().authorizationRevision(), MemoryErrorCode.VERSION_CONFLICT);
            checkSources(request.contextKey(), view.sources());
            var version = store.one("SELECT content_hash FROM memory_preference_revision WHERE block_id=? AND revision=?", request.blockId(), view.response().block().version().revision());
            require(version != null && view.response().block().version().contentHash().equals(version.get("content_hash")), MemoryErrorCode.VERSION_CONFLICT);
            MemoryV2RequestSecurity.requireUnexpired(proof);
            return view.response();
        });
    }

    private View detail(MemoryPreferenceDetailRequest request) {
        var row = header(request.contextKey(), request.blockId());
        var cursor = request.cursor() == null ? new PreferenceHistoryCursor(request.blockId(), number(row, "revision"), number(row, "authorization_revision"), 0) : request.cursor();
        require(cursor.revision() <= number(row, "revision") && cursor.authorizationRevision() == number(row, "authorization_revision"), MemoryErrorCode.VERSION_CONFLICT);
        var dependencies = dependencies(request.blockId(), cursor.revision());
        checkSources(request.contextKey(), dependencies); // locators first, before any derived preference body
        var version = store.one("SELECT * FROM memory_preference_revision WHERE block_id=? AND revision=?", request.blockId(), cursor.revision());
        require(version != null, MemoryErrorCode.VERSION_CONFLICT);
        var snapshot = store.decode((String) version.get("snapshot_json"), PreferenceBlock.class);
        require(snapshot.blockId().equals(request.blockId()) && snapshot.version().revision() == cursor.revision()
                && blockHash(snapshot).equals(version.get("content_hash")) && snapshot.version().contentHash().equals(version.get("content_hash")), MemoryErrorCode.MEMORY_UNAVAILABLE);
        var history = transitions(request.blockId(), cursor.revision());
        require(!history.isEmpty() && cursor.afterOrdinal() <= history.size(), MemoryErrorCode.INVALID_ARGUMENT);
        var chronological = history.stream().sorted(Comparator.comparing(PreferenceTransition::occurredAt)
                .thenComparing(t -> key(t.evidence().get(0).source().contextKey()))
                .thenComparingLong(t -> t.evidence().get(0).source().sourceSequence()).thenComparing(PreferenceTransition::transitionId)).toList();
        List<Object> arguments = new ArrayList<>(List.of(request.blockId(), cursor.revision()));
        String after = "";
        if (cursor.afterOrdinal() > 0) {
            var boundary = chronological.get((int) cursor.afterOrdinal()-1); var source = boundary.evidence().get(0).source();
            after = " AND (o.accepted_at,t.source_context_id,m.sequence_no,t.transition_id)>(?,?,?,?)";
            arguments.add(LocalDateTime.ofInstant(boundary.occurredAt(), ZoneOffset.UTC)); arguments.add(key(source.contextKey()));
            arguments.add(source.sourceSequence()); arguments.add(boundary.transitionId());
        }
        arguments.add(request.limit()+1);
        var pageIds = store.jdbc.queryForList("SELECT t.transition_id FROM memory_preference_transition t JOIN memory_message_observation o "
                + "ON o.context_id=t.source_context_id AND o.source_event_id=t.source_event_id JOIN memory_message m "
                + "ON m.context_id=t.source_context_id AND m.source_event_id=t.source_event_id WHERE t.block_id=? AND t.introduced_revision<=?"
                + after + " ORDER BY o.accepted_at,t.source_context_id,m.sequence_no,t.transition_id LIMIT ?", String.class, arguments.toArray());
        var byId = new HashMap<String,PreferenceTransition>(); history.forEach(t -> byId.put(t.transitionId(),t));
        List<PreferenceTransition> page = new ArrayList<>();
        for (String id : pageIds) {
            var t = byId.get(id);
            page.add(new PreferenceTransition(t.transitionId(),cursor.afterOrdinal()+page.size()+1,t.introducedRevision(),t.assessment(),t.change(),t.polarity(),t.value(),
                    t.applicability(),t.matterId(),t.occurredAt(),t.observedAt(),t.effectiveFrom(),t.effectiveUntil(),t.correctsTransitionIds(),t.confidence(),t.extractionVersion(),t.evidence()));
        }
        boolean more = page.size() > request.limit(); if (more) page = page.subList(0, request.limit());
        Long from = page.isEmpty() ? null : page.get(0).historyOrdinal(), through = page.isEmpty() ? null : page.get(page.size() - 1).historyOrdinal();
        var next = more ? new PreferenceHistoryCursor(request.blockId(), cursor.revision(), cursor.authorizationRevision(), through) : null;
        var historyPage = new PreferenceHistoryPage(cursor, page, from, through, more, next,
                more ? PreferenceHistoryPage.Completeness.PARTIAL : PreferenceHistoryPage.Completeness.COMPLETE);
        var response = new MemoryPreferenceDetailResponse(snapshot, historyPage, reducer.reduce(history, request.asOf(), request.matterId()), request.asOf());
        store.encode(response); // bounded response, never truncate and claim completeness
        return new View(response, dependencies);
    }

    private Map<String,Object> header(MemoryContextKey context, String id) {
        var row = store.one("SELECT * FROM memory_preference_block WHERE block_id=? FOR UPDATE", id);
        require(row != null, MemoryErrorCode.NOT_FOUND);
        require(context.ownerId().equals(row.get("owner_id")) && context.domainId().equals(row.get("domain_id"))
                && context.agentId().equals(row.get("agent_id")) && context.ownerId().equals(row.get("subject_ref")), MemoryErrorCode.FORBIDDEN);
        String state = (String) row.get("state");
        require(!"DELETED".equals(state) && !"DELETION_PENDING".equals(state), MemoryErrorCode.DELETED);
        require("ACTIVE".equals(state), MemoryErrorCode.WITHDRAWN);
        return row;
    }

    private List<PreferenceTransition> transitions(String id, long revision) {
        var size = store.one("SELECT COUNT(*) AS total,COALESCE(SUM(CHAR_LENGTH(payload_json)),0) AS characters FROM memory_preference_transition WHERE block_id=? AND introduced_revision<=?", id, revision);
        require(number(size, "total") <= MAX_HISTORY && number(size, "characters") <= MAX_HISTORY_CHARS, MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
        var rows = store.jdbc.queryForList("SELECT t.history_ordinal,t.introduced_revision,t.transition_id,t.transition_hash,t.source_json,t.payload_json,o.accepted_at "
                + "FROM memory_preference_transition t JOIN memory_message_observation o ON o.context_id=t.source_context_id AND o.source_event_id=t.source_event_id "
                + "WHERE t.block_id=? AND t.introduced_revision<=? ORDER BY t.history_ordinal LIMIT 10001", id, revision);
        List<PreferenceTransition> result = new ArrayList<>(); long ordinal = 0;
        for (var row : rows) {
            var transition = store.decode((String) row.get("payload_json"), PreferenceTransition.class);
            require(transition.historyOrdinal() == ++ordinal && transition.historyOrdinal() == number(row, "history_ordinal")
                    && transition.introducedRevision() == number(row, "introduced_revision") && transition.transitionId().equals(row.get("transition_id"))
                    && transition.evidence().equals(List.of(store.decode((String) row.get("source_json"), MemorySourceDependency.class)))
                    && transition.occurredAt().equals(utcInstant(row.get("accepted_at")))
                    && MemoryCanonicalHash.contentHash(transition).equals(row.get("transition_hash")), MemoryErrorCode.MEMORY_UNAVAILABLE);
            result.add(transition);
        }
        require(result.size() == revision, MemoryErrorCode.MEMORY_UNAVAILABLE);
        return result;
    }

    private List<MemorySourceDependency> dependencies(String id, long revision) {
        var size = store.one("SELECT COUNT(*) AS total,COALESCE(SUM(CHAR_LENGTH(source_json)),0) AS characters FROM memory_preference_transition WHERE block_id=? AND introduced_revision<=?", id, revision);
        require(number(size,"total") == revision, MemoryErrorCode.MEMORY_UNAVAILABLE);
        require(number(size,"total") <= MAX_HISTORY && number(size,"characters") <= MAX_HISTORY_CHARS, MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
        var rows = store.jdbc.queryForList("SELECT source_json FROM memory_preference_transition WHERE block_id=? AND introduced_revision<=? ORDER BY history_ordinal LIMIT 10001", String.class, id, revision);
        require(rows.size() <= MAX_HISTORY, MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
        return rows.stream().map(json -> store.decode(json,MemorySourceDependency.class)).distinct().toList();
    }

    private static final java.util.regex.Pattern CREDENTIAL = java.util.regex.Pattern.compile("(?i)(?:\\bBearer\\s+\\S+|\\b(?:password|passwd|access[_-]?token|refresh[_-]?token|client[_-]?secret|authorization)\\b\\s*[:=]\\s*\\S+|\\beyJ[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{8,})");
    private void safeText(String text) { require(text == null || !CREDENTIAL.matcher(text).find(), MemoryErrorCode.INVALID_EXTRACTION); }
    private void safe(MemoryJsonValue value) {
        safeText(value.scalar());
        if(value.elements()!=null) value.elements().forEach(this::safe);
        if(value.properties()!=null) value.properties().forEach((name,child) -> {
            require(!name.matches("(?i).*(token|password|secret|beacon|authorization|desensiti[sz]ationmap).*"),MemoryErrorCode.INVALID_EXTRACTION); safe(child);
        });
    }

    private void checkSources(MemoryContextKey reader, List<MemorySourceDependency> dependencies) {
        // This aggregate is private to an owner/domain/agent across that agent's conversations.
        for (var dependency : dependencies) {
            var source = dependency.source().contextKey();
            require(dependency.grant() == null && reader.ownerId().equals(source.ownerId()) && reader.domainId().equals(source.domainId())
                    && reader.agentId().equals(source.agentId()), MemoryErrorCode.FORBIDDEN);
            sources.requireCurrentMetadata(source, List.of(dependency));
        }
    }

    static String blockHash(PreferenceBlock block) {
        return MemoryCanonicalHash.contentHash(List.of(block.blockId(), block.key(), block.version().revision(), block.version().schemaVersion(),
                block.state(), block.currentState(), block.historyStart()));
    }

    private <T> T transaction(Supplier<T> work) {
        try { return tx.execute(status -> work.get()); }
        catch (MemoryException exception) { throw exception; }
        catch (DataAccessException exception) { throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE); }
    }
}
