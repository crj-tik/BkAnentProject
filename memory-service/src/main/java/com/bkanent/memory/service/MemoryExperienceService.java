package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.memory.config.MemoryExperienceProperties;
import com.bkanent.memory.config.MemoryV2SecurityProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static com.bkanent.memory.service.MemoryArchiveStore.*;

/** Private, versioned experience intake, processing, lifecycle and selection. */
@Service
public class MemoryExperienceService {
    private static final String SCHEMA_VERSION = "experience.v1";
    private static final String ASSESSMENT_VERSION = "experience-assessment-v1";
    private static final int MAX_EVIDENCE = 500;
    private static final Pattern CREDENTIAL = Pattern.compile("(?i)(?:\\bBearer\\s+\\S+|\\b(?:password|passwd|passcode|access[_-]?token|refresh[_-]?token|client[_-]?secret|api[_-]?key|secret|authorization)\\b\\s*[:=]\\s*\\S+|\\beyJ[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{8,}|\\bsk-[A-Za-z0-9_-]{16,})");

    private final MemoryArchiveStore store;
    private final MemoryV2RequestSecurity security;
    private final MemorySourceAuthorizer sources;
    private final MemoryExperienceProperties properties;
    private final MemoryV2SecurityProperties callers;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ExperienceSelectionPolicy selector = new ExperienceSelectionPolicy();

    public MemoryExperienceService(MemoryArchiveStore store, MemoryV2RequestSecurity security, MemorySourceAuthorizer sources,
            MemoryExperienceProperties properties, MemoryV2SecurityProperties callers, PlatformTransactionManager manager) {
        this(store, security, sources, properties, callers, manager, Clock.systemUTC());
    }

    MemoryExperienceService(MemoryArchiveStore store, MemoryV2RequestSecurity security, MemorySourceAuthorizer sources,
            MemoryExperienceProperties properties, MemoryV2SecurityProperties callers, PlatformTransactionManager manager, Clock clock) {
        this.store = store;
        this.security = security;
        this.sources = sources;
        this.properties = properties;
        this.callers = callers;
        this.clock = clock;
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(10);
    }

    /** SERVICE lane: only a configured result publisher may assert an immutable domain outcome. */
    public MemoryExperienceEventStatusResponse submitOutcome(MemoryExperienceOutcomeRequest request, MemoryRequestProof proof) {
        long owner = security.authorizeExperienceOutcome(request, proof);
        return transaction(() -> {
            requireEnabled();
            require(owner == owner(request.contextKey()), MemoryErrorCode.FORBIDDEN);
            requirePublisher(request.envelope().caller(), request.contextKey());
            var prior = existingReceipt(request, request.evidenceId());
            if (prior != null) return prior;
            requireOccurredAt(request.occurredAt());
            requireSafeText(request.scenario());
            requireSafeText(request.applicability());
            requireSafeText(request.conclusion());
            verifyExpectedRevision(request.contextKey(), request.experienceId(), request.expectedRevision(), true);
            return enqueue(request, request.evidenceId(), request.experienceId(), request.expectedRevision(),
                    MemoryExperience.EvidenceKind.BUSINESS_OUTCOME, request.source(), request.occurredAt());
        });
    }

    /** USER lane: feedback is anchored to that owner's current archived USER message and one exact experience revision. */
    public MemoryExperienceEventStatusResponse submitFeedback(MemoryExperienceFeedbackRequest request, MemoryRequestProof proof) {
        long owner = security.authorize(request, proof, MemoryV2Contract.OPERATION_EXPERIENCE_FEEDBACK);
        return transaction(() -> {
            requireEnabled();
            require(owner == owner(request.contextKey()), MemoryErrorCode.FORBIDDEN);
            var prior = existingReceipt(request, request.evidenceId());
            if (prior != null) return prior;
            requireOccurredAt(request.occurredAt());
            verifyExpectedRevision(request.contextKey(), request.experienceId(), request.expectedRevision(), false);
            verifyFeedbackSource(request.contextKey(), request.source());
            return enqueue(request, request.evidenceId(), request.experienceId(), request.expectedRevision(),
                    MemoryExperience.EvidenceKind.USER_FEEDBACK, request.source(), request.occurredAt());
        });
    }

    /** User-readable status contains only one event's state and safe error code. */
    public MemoryExperienceEventStatusResponse status(MemoryExperienceEventStatusRequest request, MemoryRequestProof proof) {
        long owner = security.authorize(request, proof, MemoryV2Contract.OPERATION_EXPERIENCE_STATUS);
        var result = transaction(() -> statusAt(request.contextKey(), request.evidenceId(), owner));
        security.revalidate(request, proof, owner, MemoryV2Contract.OPERATION_EXPERIENCE_STATUS);
        MemoryV2RequestSecurity.requireUnexpired(proof);
        return result;
    }

    /** Explicit deactivation and evidence-backed reactivation are new immutable revisions. */
    public MemoryExperienceLifecycleResponse changeLifecycle(MemoryExperienceLifecycleRequest request, MemoryRequestProof proof) {
        long owner = security.authorize(request, proof, MemoryV2Contract.OPERATION_EXPERIENCE_LIFECYCLE);
        var result = transaction(() -> {
            requireEnabled();
            require(owner == owner(request.contextKey()), MemoryErrorCode.FORBIDDEN);
            var current = currentExperience(request.contextKey(), request.experienceId(), true);
            require(current.experience().version().revision() == request.expectedRevision(), MemoryErrorCode.VERSION_CONFLICT);
            MemoryState target;
            double activation;
            if (request.action() == MemoryExperienceLifecycleRequest.Action.DEACTIVATE) {
                require(current.experience().state() == MemoryState.ACTIVE, MemoryErrorCode.VERSION_CONFLICT);
                target = MemoryState.INACTIVE;
                activation = 0.0;
            } else {
                require(current.experience().state() == MemoryState.INACTIVE, MemoryErrorCode.VERSION_CONFLICT);
                var evidence = current.experience().evidence().stream()
                        .filter(item -> item.evidenceId().equals(request.evidenceId()) && item.verified() && positive(item))
                        .findFirst().orElseThrow(() -> new MemoryException(MemoryErrorCode.FORBIDDEN));
                for (var dependency : evidence.sources()) sources.requireCurrentMetadata(dependency.source().contextKey(), List.of(dependency));
                target = MemoryState.ACTIVE;
                activation = 0.5;
            }
            var updated = revise(current.experience(), current.experience().version().revision() + 1, target, activation,
                    "lifecycle:" + request.action().name() + ":" + request.reason(), current.experience().evidence(),
                    current.experience().confidence(), current.experience().lastObservedAt(), current.experience().lastValidatedAt());
            persist(updated);
            invalidateSelections(request.contextKey().ownerId(), request.contextKey().domainId(), request.contextKey().agentId());
            MemoryV2RequestSecurity.requireUnexpired(proof);
            return new MemoryExperienceLifecycleResponse(updated.experienceId(), updated.version().revision(), updated.state(), updated.changeReason());
        });
        security.revalidate(request, proof, owner, MemoryV2Contract.OPERATION_EXPERIENCE_LIFECYCLE);
        return result;
    }

    /** A deterministic leased assessment of the exact accepted event; no LLM, tool or business call is made. */
    void process(String contextId, String evidenceId, String leaseOwner, int attempt) {
        try {
            transaction(() -> {
                var job = store.one("SELECT * FROM memory_processing_job WHERE job_id=? FOR UPDATE", jobId(contextId, evidenceId));
                require(job != null && "PROCESSING".equals(job.get("state")) && leaseOwner.equals(job.get("lease_owner"))
                        && attempt == number(job, "attempts") && utcInstant(job.get("lease_until")).isAfter(clock.instant()), MemoryErrorCode.VERSION_CONFLICT);
                var event = store.one("SELECT * FROM memory_experience_event WHERE context_id=? AND evidence_id=? FOR UPDATE", contextId, evidenceId);
                require(event != null, MemoryErrorCode.NOT_FOUND);
                require("ACTIVE".equals(event.get("source_state")) && event.get("request_json") != null, sourceStateError(event));
                MemoryContextKey eventContext = context(event);
                require(key(eventContext).equals(contextId), MemoryErrorCode.FORBIDDEN);
                String kind = (String) event.get("event_kind");
                MemorySourceDependency source;
                ExperienceView current = null;
                String experienceId = (String) event.get("experience_id");
                long expectedRevision = number(event, "expected_revision");
                MemoryExperience.Evidence evidence;
                String scenario;
                String applicability;
                String conclusion;
                MemoryAssociation association;
                Instant occurredAt;
                if ("BUSINESS_OUTCOME".equals(kind)) {
                    var request = store.decode((String) event.get("request_json"), MemoryExperienceOutcomeRequest.class);
                    require(request.envelope().bodyHash().equals(event.get("request_hash"))
                            && request.evidenceId().equals(evidenceId) && request.contextKey().equals(eventContext), MemoryErrorCode.MEMORY_UNAVAILABLE);
                    requirePublisher(request.envelope().caller(), request.contextKey());
                    source = request.source();
                    sources.requireCurrentMetadata(source.source().contextKey(), List.of(source));
                    current = currentExperienceOrNull(eventContext, experienceId, true);
                    verifyRevision(current, expectedRevision, true);
                    scenario = request.scenario(); applicability = request.applicability(); conclusion = request.conclusion();
                    association = request.association(); occurredAt = request.occurredAt();
                    evidence = new MemoryExperience.Evidence(evidenceId, MemoryExperience.EvidenceKind.BUSINESS_OUTCOME,
                            request.outcome(), MemoryExperience.Feedback.NONE, true, occurredAt, nowInstant(), List.of(source));
                } else {
                    require("USER_FEEDBACK".equals(kind), MemoryErrorCode.MEMORY_UNAVAILABLE);
                    var request = store.decode((String) event.get("request_json"), MemoryExperienceFeedbackRequest.class);
                    require(request.envelope().bodyHash().equals(event.get("request_hash"))
                            && request.evidenceId().equals(evidenceId) && request.contextKey().equals(eventContext), MemoryErrorCode.MEMORY_UNAVAILABLE);
                    source = request.source();
                    verifyFeedbackSource(eventContext, source);
                    current = currentExperienceOrNull(eventContext, experienceId, true);
                    verifyRevision(current, expectedRevision, false);
                    require(current.experience().association().equals(request.association()), MemoryErrorCode.VERSION_CONFLICT);
                    scenario = current.experience().scenario(); applicability = current.experience().applicability();
                    conclusion = current.experience().conclusion(); association = current.experience().association(); occurredAt = request.occurredAt();
                    evidence = new MemoryExperience.Evidence(evidenceId, MemoryExperience.EvidenceKind.USER_FEEDBACK,
                            MemoryExperience.Outcome.UNKNOWN, request.feedback(), true, occurredAt, nowInstant(), List.of(source));
                }
                require(current == null || current.experience().evidence().stream().noneMatch(item -> item.evidenceId().equals(evidenceId)),
                        MemoryErrorCode.VERSION_CONFLICT);
                List<MemoryExperience.Evidence> evidenceList = new ArrayList<>();
                if (current != null) evidenceList.addAll(current.experience().evidence());
                require(evidenceList.size() < MAX_EVIDENCE, MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
                evidenceList.add(evidence);
                var assessment = assess(evidenceList, evidence, current == null ? MemoryState.ACTIVE : current.experience().state());
                Instant observed = evidence.observedAt();
                Instant lastObserved = later(current == null ? null : current.experience().lastObservedAt(), observed);
                Instant lastValidated = later(current == null ? null : current.experience().lastValidatedAt(), observed);
                long revision = current == null ? 1 : current.experience().version().revision() + 1;
                String reason = "evidence:" + evidenceId + ":" + kind;
                var updated = build(experienceId, current == null ? eventContext : current.experience().contextKey(), association,
                        revision, scenario, applicability, conclusion, assessment.state(), reason,
                        current == null ? null : current.experience().version().revision(), assessment.confidence(),
                        assessment.activation(), lastObserved, lastValidated, evidenceList);
                persist(updated);
                store.jdbc.update("UPDATE memory_experience_event SET updated_at=? WHERE context_id=? AND evidence_id=?", now(), contextId, evidenceId);
                store.jdbc.update("UPDATE memory_processing_job SET state='SUCCEEDED',last_error=NULL,lease_owner=NULL,lease_until=NULL WHERE job_id=?",
                        jobId(contextId, evidenceId));
                invalidateSelections(eventContext.ownerId(), eventContext.domainId(), eventContext.agentId());
                return null;
            });
        } catch (RuntimeException exception) {
            fail(contextId, evidenceId, leaseOwner, attempt, exception);
        }
    }

    public MemoryExperienceQueryResponse search(MemoryExperienceQueryRequest request, MemoryRequestProof proof) {
        long owner = security.authorize(request, proof, MemoryV2Contract.OPERATION_SEARCH_EXPERIENCES);
        require(owner == owner(request.contextKey()), MemoryErrorCode.FORBIDDEN);
        require(properties.isEnabled(), MemoryErrorCode.MEMORY_UNAVAILABLE);
        require(request.tokenBudget() <= properties.getMaxTokenBudget(), MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
        Instant asOf = nowInstant();
        var first = transaction(() -> assemble(request, asOf));
        security.revalidate(request, proof, owner, MemoryV2Contract.OPERATION_SEARCH_EXPERIENCES);
        var checked = transaction(() -> {
            var current = assemble(request, asOf);
            require(first.equals(current), MemoryErrorCode.VERSION_CONFLICT);
            MemoryV2RequestSecurity.requireUnexpired(proof);
            return current;
        });
        return checked;
    }

    private MemoryExperienceQueryResponse assemble(MemoryExperienceQueryRequest request, Instant asOf) {
        MemoryContextKey context = request.contextKey();
        store.conversation(context);
        String objective = request.objective();
        Long matterRevision = null;
        String matterId = request.matterId();
        if (matterId != null) {
            require(request.cursor() == null, MemoryErrorCode.INVALID_ARGUMENT);
            require(objective == null, MemoryErrorCode.INVALID_ARGUMENT);
            var matter = currentMatter(context, matterId);
            objective = matter.goal();
            matterRevision = matter.version().revision();
        }
        String objectiveHash = MemoryCanonicalHash.contentHash(Arrays.asList(objective, request.capabilityTag(), request.limit(), request.cursor()));
        if (matterId != null) {
            var cached = cachedSelection(context, matterId, matterRevision, objectiveHash, request.tokenBudget());
            if (cached != null) return cached;
        }

        int pageLimit = Math.min(request.limit(), properties.getMaxCandidates());
        int fetchLimit = Math.min(pageLimit + 1, properties.getMaxCandidates() + 1);
        String sql = "SELECT experience_id FROM memory_experience WHERE owner_id=? AND domain_id=? AND agent_id=? AND state='ACTIVE' "
                + "AND (retention_until IS NULL OR retention_until>?) " + (request.cursor() == null ? "" : "AND experience_id>? ")
                + "ORDER BY experience_id LIMIT ?";
        List<Object> args = new ArrayList<>(List.of(context.ownerId(), context.domainId(), context.agentId(), now()));
        if (request.cursor() != null) args.add(request.cursor());
        args.add(fetchLimit);
        var ids = store.jdbc.query(sql, (rs, n) -> rs.getString(1), args.toArray());
        boolean more = ids.size() > pageLimit;
        List<String> scanned = ids.subList(0, Math.min(pageLimit, ids.size()));
        List<MemoryExperienceMatch> candidates = new ArrayList<>();
        for (String id : scanned) {
            var current = currentExperience(context, id, false);
            var experience = current.experience();
            if (experience.state() != MemoryState.ACTIVE) continue;
            double relevance = selector.relevance(objective, request.capabilityTag(), experience);
            if (relevance <= 0) continue;
            double timeWeight = selector.timeWeight(experience.lastValidatedAt(), experience.lastObservedAt(), asOf, properties.getHalfLifeDays());
            double score = relevance * experience.confidence() * experience.activation() * timeWeight;
            var scoreView = new MemoryExperienceScore(relevance, experience.confidence(), experience.activation(), timeWeight,
                    Math.max(0, Math.min(1, score)), asOf, ExperienceSelectionPolicy.VERSION);
            var estimate = new MemoryExperienceMatch(experience, scoreView, 1);
            int tokens = selector.estimatedTokens(store.encode(estimate).getBytes(StandardCharsets.UTF_8));
            candidates.add(new MemoryExperienceMatch(experience, scoreView, tokens));
        }
        candidates.sort(Comparator.comparingDouble((MemoryExperienceMatch match) -> match.score().score()).reversed()
                .thenComparing(match -> match.experience().experienceId()));
        List<MemoryExperienceMatch> selected = new ArrayList<>();
        int used = 0;
        boolean budgetLimited = false;
        for (var match : candidates) {
            if (match.estimatedTokens() <= request.tokenBudget() - used) {
                selected.add(match);
                used += match.estimatedTokens();
            } else budgetLimited = true;
        }
        String cursor = more && !scanned.isEmpty() ? scanned.get(scanned.size() - 1) : null;
        var response = new MemoryExperienceQueryResponse(selected, cursor, more, ExperienceSelectionPolicy.VERSION, asOf,
                matterId, matterRevision, objectiveHash, used, budgetLimited);
        if (matterId != null) {
            store.jdbc.update("INSERT INTO memory_experience_selection(context_id,matter_id,matter_revision,objective_hash,scoring_version,token_budget,selection_json,updated_at) "
                            + "VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE matter_revision=VALUES(matter_revision),objective_hash=VALUES(objective_hash),"
                            + "scoring_version=VALUES(scoring_version),token_budget=VALUES(token_budget),selection_json=VALUES(selection_json),updated_at=VALUES(updated_at)",
                    key(context), matterId, matterRevision, objectiveHash, ExperienceSelectionPolicy.VERSION,
                    request.tokenBudget(), store.encode(response), now());
        }
        return response;
    }

    private MemoryExperienceQueryResponse cachedSelection(MemoryContextKey context, String matterId, long matterRevision,
            String objectiveHash, int tokenBudget) {
        var row = store.one("SELECT selection_json FROM memory_experience_selection WHERE context_id=? AND matter_id=? AND matter_revision=? "
                        + "AND objective_hash=? AND scoring_version=? AND token_budget=? FOR UPDATE",
                key(context), matterId, matterRevision, objectiveHash, ExperienceSelectionPolicy.VERSION, tokenBudget);
        if (row == null || row.get("selection_json") == null) return null;
        try {
            var response = store.decode((String) row.get("selection_json"), MemoryExperienceQueryResponse.class);
            require(response.matterId().equals(matterId) && Objects.equals(response.matterRevision(), matterRevision)
                    && response.objectiveHash().equals(objectiveHash) && response.scoringVersion().equals(ExperienceSelectionPolicy.VERSION),
                    MemoryErrorCode.MEMORY_UNAVAILABLE);
            for (var match : response.matches()) {
                var current = currentExperience(context, match.experience().experienceId(), false);
                require(current.experience().version().equals(match.experience().version())
                        && current.experience().state() == MemoryState.ACTIVE, MemoryErrorCode.VERSION_CONFLICT);
            }
            return response;
        } catch (MemoryException exception) {
            // Stale selections are discarded and rebuilt from current truth; authentication is checked outside this cache.
            return null;
        }
    }

    private MemoryMatter currentMatter(MemoryContextKey context, String matterId) {
        var header = store.one("SELECT content_revision,state FROM memory_matter WHERE context_id=? AND matter_id=? FOR UPDATE", key(context), matterId);
        require(header != null && Set.of("ACTIVE", "REOPENED").contains(header.get("state")), MemoryErrorCode.NOT_FOUND);
        long revision = number(header, "content_revision");
        var dependencies = dependenciesForTarget(context, "MATTER", matterId, revision);
        sources.requireCurrentMetadata(context, dependencies);
        var row = store.one("SELECT payload_json FROM memory_matter WHERE context_id=? AND matter_id=? AND content_revision=? AND state=?",
                key(context), matterId, revision, header.get("state"));
        require(row != null && row.get("payload_json") != null, MemoryErrorCode.VERSION_CONFLICT);
        var matter = store.decode((String) row.get("payload_json"), MemoryMatter.class);
        require(matter.contextKey().equals(context) && matter.matterId().equals(matterId) && matter.version().revision() == revision
                && MemoryCanonicalHash.matterHash(matter).equals(matter.version().contentHash()), MemoryErrorCode.MEMORY_UNAVAILABLE);
        return matter;
    }

    private List<MemorySourceDependency> dependenciesForTarget(MemoryContextKey context, String kind, String targetId, long revision) {
        return store.jdbc.query("SELECT dependency_json FROM memory_source_dependency WHERE target_context_id=? AND target_kind=? AND target_id=? AND target_revision=? LIMIT 501",
                (rs, n) -> store.decode(rs.getString(1), MemorySourceDependency.class), key(context), kind, targetId, revision);
    }

    private MemoryExperienceEventStatusResponse enqueue(MemoryV2Request request, String evidenceId, String experienceId,
            long expectedRevision, MemoryExperience.EvidenceKind kind, MemorySourceDependency dependency, Instant occurredAt) {
        MemoryContextKey context = request.contextKey();
        store.conversation(context);
        String contextId = key(context);
        var existing = store.one("SELECT context_id,request_hash FROM memory_experience_event WHERE owner_id=? AND domain_id=? AND agent_id=? AND evidence_id=? FOR UPDATE",
                context.ownerId(), context.domainId(), context.agentId(), evidenceId);
        if (existing != null) {
            require(contextId.equals(existing.get("context_id")), MemoryErrorCode.VERSION_CONFLICT);
            require(request.envelope().bodyHash().equals(existing.get("request_hash")), MemoryErrorCode.VERSION_CONFLICT);
            return statusAt(context, evidenceId, owner(context));
        }
        var source = dependency.source();
        LocalDateTime accepted = now();
        store.jdbc.update("INSERT INTO memory_experience_event(context_id,owner_id,domain_id,agent_id,evidence_id,experience_id,expected_revision,event_kind,"
                        + "source_caller,request_hash,request_json,source_context_id,source_kind,source_id,source_event_id,source_revision,source_hash,source_schema_version,"
                        + "source_state,occurred_at,accepted_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'ACTIVE',?,?,?) "
                        + "ON DUPLICATE KEY UPDATE evidence_id=evidence_id",
                contextId, context.ownerId(), context.domainId(), context.agentId(), evidenceId, experienceId, expectedRevision,
                kind.name(), request.envelope().caller(), request.envelope().bodyHash(), store.encode(request), key(source.contextKey()), source.kind().name(),
                source.sourceId(), source.sourceEventId(), source.version().revision(), source.version().contentHash(), source.version().schemaVersion(),
                sqlTime(occurredAt), accepted, accepted);
        var acceptedEvent = store.one("SELECT context_id,request_hash FROM memory_experience_event WHERE owner_id=? AND domain_id=? AND agent_id=? AND evidence_id=? FOR UPDATE",
                context.ownerId(), context.domainId(), context.agentId(), evidenceId);
        require(acceptedEvent != null && contextId.equals(acceptedEvent.get("context_id"))
                && request.envelope().bodyHash().equals(acceptedEvent.get("request_hash")), MemoryErrorCode.VERSION_CONFLICT);
        store.jdbc.update("INSERT INTO memory_processing_job(job_id,context_id,source_event_id,job_kind,available_at) VALUES(?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE job_id=job_id",
                jobId(contextId, evidenceId), contextId, evidenceId, "ASSESS_EXPERIENCE", accepted);
        return statusAt(context, evidenceId, owner(context));
    }

    private MemoryExperienceEventStatusResponse existingReceipt(MemoryV2Request request, String evidenceId) {
        MemoryContextKey context = request.contextKey();
        String contextId = key(context);
        var existing = store.one("SELECT context_id,request_hash FROM memory_experience_event WHERE owner_id=? AND domain_id=? AND agent_id=? AND evidence_id=? FOR UPDATE",
                context.ownerId(), context.domainId(), context.agentId(), evidenceId);
        if (existing == null) return null;
        require(contextId.equals(existing.get("context_id")), MemoryErrorCode.VERSION_CONFLICT);
        require(request.envelope().bodyHash().equals(existing.get("request_hash")), MemoryErrorCode.VERSION_CONFLICT);
        return statusAt(context, evidenceId, owner(context));
    }

    private MemoryExperienceEventStatusResponse statusAt(MemoryContextKey context, String evidenceId, long owner) {
        var event = store.one("SELECT source_state,accepted_at,updated_at FROM memory_experience_event WHERE context_id=? AND evidence_id=? AND owner_id=? AND domain_id=? AND agent_id=?",
                key(context), evidenceId, Long.toString(owner), context.domainId(), context.agentId());
        require(event != null, MemoryErrorCode.NOT_FOUND);
        String eventState = (String) event.get("source_state");
        var job = store.one("SELECT state,attempts,last_error FROM memory_processing_job WHERE job_id=?", jobId(key(context), evidenceId));
        require(job != null, MemoryErrorCode.MEMORY_UNAVAILABLE);
        MemoryExperienceEventStatusResponse.State state;
        if ("DELETED".equals(eventState)) state = MemoryExperienceEventStatusResponse.State.DELETED;
        else if ("WITHDRAWN".equals(eventState)) state = MemoryExperienceEventStatusResponse.State.WITHDRAWN;
        else state = MemoryExperienceEventStatusResponse.State.valueOf((String) job.get("state"));
        return new MemoryExperienceEventStatusResponse(evidenceId, state, (int) number(job, "attempts"),
                (String) job.get("last_error"), utcInstant(event.get("accepted_at")), utcInstant(event.get("updated_at")));
    }

    private void verifyExpectedRevision(MemoryContextKey context, String experienceId, long expectedRevision, boolean allowCreate) {
        var header = store.one("SELECT revision,state FROM memory_experience WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=? FOR UPDATE",
                context.ownerId(), context.domainId(), context.agentId(), experienceId);
        if (header == null) {
            require(allowCreate && expectedRevision == 0, MemoryErrorCode.NOT_FOUND);
            return;
        }
        require(!Set.of("DELETION_PENDING", "DELETED").contains(header.get("state")), MemoryErrorCode.DELETED);
        require(!"WITHDRAWN".equals(header.get("state")), MemoryErrorCode.WITHDRAWN);
        require(number(header, "revision") == expectedRevision, MemoryErrorCode.VERSION_CONFLICT);
    }

    private void verifyFeedbackSource(MemoryContextKey requester, MemorySourceDependency dependency) {
        var ref = dependency.source();
        require(dependency.grant() == null && ref.kind() == MemorySourceRef.Kind.MESSAGE && ref.sourceEventId() != null,
                MemoryErrorCode.FORBIDDEN);
        require(samePrivateScope(requester, ref.contextKey()), MemoryErrorCode.FORBIDDEN);
        sources.requireCurrentMetadata(ref.contextKey(), List.of(dependency));
        var row = store.one("SELECT payload_json FROM memory_message WHERE context_id=? AND message_id=? AND source_event_id=?",
                key(ref.contextKey()), ref.sourceId(), ref.sourceEventId());
        require(row != null && row.get("payload_json") != null, MemoryErrorCode.ARCHIVE_NOT_CONFIRMED);
        var message = store.decode((String) row.get("payload_json"), MemoryMessage.class);
        require(message.role() == MemoryMessage.Role.USER && message.source().equals(ref)
                && MemoryCanonicalHash.messageHash(message).equals(ref.version().contentHash()), MemoryErrorCode.FORBIDDEN);
    }

    private ExperienceView currentExperience(MemoryContextKey scope, String experienceId, boolean lock) {
        var experience = currentExperienceOrNull(scope, experienceId, lock);
        if (experience == null) throw new MemoryException(MemoryErrorCode.NOT_FOUND);
        return experience;
    }

    private ExperienceView currentExperienceOrNull(MemoryContextKey scope, String experienceId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        var header = store.one("SELECT revision,content_hash,schema_version,state FROM memory_experience WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=?" + suffix,
                scope.ownerId(), scope.domainId(), scope.agentId(), experienceId);
        if (header == null) return null;
        String state = (String) header.get("state");
        require(!"DELETED".equals(state) && !"DELETION_PENDING".equals(state), MemoryErrorCode.DELETED);
        require(!"WITHDRAWN".equals(state), MemoryErrorCode.WITHDRAWN);
        long revision = number(header, "revision");
        List<MemorySourceDependency> dependencies = experienceDependencies(scope, experienceId, revision);
        for (var dependency : dependencies) {
            require(samePrivateScope(scope, dependency.source().contextKey()), MemoryErrorCode.FORBIDDEN);
            sources.requireCurrentMetadata(dependency.source().contextKey(), List.of(dependency));
        }
        var body = store.one("SELECT payload_json FROM memory_experience_revision WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=? AND revision=?",
                scope.ownerId(), scope.domainId(), scope.agentId(), experienceId, revision);
        require(body != null && body.get("payload_json") != null, MemoryErrorCode.VERSION_CONFLICT);
        var value = store.decode((String) body.get("payload_json"), MemoryExperience.class);
        require(value.experienceId().equals(experienceId) && samePrivateScope(scope, value.contextKey())
                && value.version().revision() == revision && value.version().contentHash().equals(header.get("content_hash"))
                && value.version().schemaVersion().equals(header.get("schema_version"))
                && value.state().name().equals(state) && MemoryCanonicalHash.experienceHash(value).equals(header.get("content_hash"))
                && new HashSet<>(dependencies).equals(evidenceDependencies(value)), MemoryErrorCode.MEMORY_UNAVAILABLE);
        return new ExperienceView(value, header);
    }

    private MemoryContextKey context(Map<String, Object> event) {
        var row = store.one("SELECT owner_id,domain_id,agent_id,conversation_id FROM memory_conversation WHERE context_id=?", event.get("context_id"));
        require(row != null, MemoryErrorCode.FORBIDDEN);
        return new MemoryContextKey((String) row.get("owner_id"), (String) row.get("domain_id"),
                (String) row.get("agent_id"), (String) row.get("conversation_id"));
    }

    private List<MemorySourceDependency> experienceDependencies(MemoryContextKey scope, String experienceId, long revision) {
        var rows = store.jdbc.queryForList("SELECT source_context_id,source_kind,source_id,source_event_id,source_sequence,source_revision,source_hash,source_schema_version,grant_id,grant_revision "
                + "FROM memory_experience_evidence_source WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=? "
                + "AND experience_revision=? AND state='ACTIVE' ORDER BY evidence_id,source_link_hash",
                scope.ownerId(), scope.domainId(), scope.agentId(), experienceId, revision);
        List<MemorySourceDependency> result = new ArrayList<>();
        for (var row : rows) {
            require(row.get("grant_id") == null && row.get("grant_revision") == null, MemoryErrorCode.FORBIDDEN);
            String sourceContextId = (String) row.get("source_context_id");
            var conversation = store.one("SELECT owner_id,domain_id,agent_id,conversation_id FROM memory_conversation WHERE context_id=?", sourceContextId);
            require(conversation != null, MemoryErrorCode.FORBIDDEN);
            var context = new MemoryContextKey((String) conversation.get("owner_id"), (String) conversation.get("domain_id"),
                    (String) conversation.get("agent_id"), (String) conversation.get("conversation_id"));
            var source = new MemorySourceRef(MemorySourceRef.Kind.valueOf((String) row.get("source_kind")), context,
                    (String) row.get("source_id"), (String) row.get("source_event_id"),
                    row.get("source_sequence") == null ? null : ((Number) row.get("source_sequence")).longValue(),
                    new MemoryRevision(number(row, "source_revision"), (String) row.get("source_hash"), (String) row.get("source_schema_version")));
            result.add(new MemorySourceDependency(source, null));
        }
        return result;
    }

    private static Set<MemorySourceDependency> evidenceDependencies(MemoryExperience experience) {
        Set<MemorySourceDependency> result = new HashSet<>();
        experience.evidence().forEach(evidence -> result.addAll(evidence.sources()));
        return result;
    }

    private void persist(MemoryExperience experience) {
        require(experience.version().revision() == 1 || experience.supersedesRevision() != null, MemoryErrorCode.VERSION_CONFLICT);
        long revision = experience.version().revision();
        String hash = experience.version().contentHash();
        var context = experience.contextKey();
        if (revision == 1) {
            store.jdbc.update("INSERT INTO memory_experience(owner_id,domain_id,agent_id,experience_id,revision,content_hash,schema_version,state,confidence,activation,last_observed_at,last_validated_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    context.ownerId(), context.domainId(), context.agentId(), experience.experienceId(), revision, hash, SCHEMA_VERSION,
                    experience.state().name(), experience.confidence(), experience.activation(), sqlTime(experience.lastObservedAt()),
                    experience.lastValidatedAt() == null ? null : sqlTime(experience.lastValidatedAt()));
        } else {
            int changed = store.jdbc.update("UPDATE memory_experience SET revision=?,content_hash=?,schema_version=?,lifecycle_revision=lifecycle_revision+1,state=?,confidence=?,activation=?,"
                            + "last_observed_at=?,last_validated_at=?,updated_at=? WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=? AND revision=?",
                    revision, hash, SCHEMA_VERSION, experience.state().name(), experience.confidence(), experience.activation(),
                    sqlTime(experience.lastObservedAt()), experience.lastValidatedAt() == null ? null : sqlTime(experience.lastValidatedAt()), now(),
                    context.ownerId(), context.domainId(), context.agentId(), experience.experienceId(), experience.supersedesRevision());
            require(changed == 1, MemoryErrorCode.VERSION_CONFLICT);
        }
        store.jdbc.update("INSERT INTO memory_experience_revision(owner_id,domain_id,agent_id,experience_id,revision,content_hash,schema_version,payload_json) VALUES(?,?,?,?,?,?,?,?)",
                context.ownerId(), context.domainId(), context.agentId(), experience.experienceId(), revision, hash, SCHEMA_VERSION, store.encode(experience));
        for (var evidence : experience.evidence()) {
            store.jdbc.update("INSERT INTO memory_experience_evidence(owner_id,domain_id,agent_id,experience_id,experience_revision,evidence_id,evidence_kind,outcome,feedback,verified,occurred_at,observed_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    context.ownerId(), context.domainId(), context.agentId(), experience.experienceId(), revision, evidence.evidenceId(), evidence.kind().name(),
                    evidence.outcome().name(), evidence.feedback().name(), evidence.verified(), sqlTime(evidence.occurredAt()), sqlTime(evidence.observedAt()));
            for (var dependency : evidence.sources()) persistSource(experience, revision, evidence.evidenceId(), dependency);
        }
    }

    private void persistSource(MemoryExperience experience, long revision, String evidenceId, MemorySourceDependency dependency) {
        var source = dependency.source();
        String linkHash = MemoryCanonicalHash.contentHash(Arrays.asList(experience.contextKey().ownerId(), experience.contextKey().domainId(),
                experience.contextKey().agentId(), experience.experienceId(), revision, evidenceId, dependency));
        store.jdbc.update("INSERT INTO memory_experience_evidence_source(source_link_hash,owner_id,domain_id,agent_id,experience_id,experience_revision,evidence_id,"
                        + "source_context_id,source_kind,source_id,source_event_id,source_sequence,source_revision,source_hash,source_schema_version,grant_id,grant_revision) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                linkHash, experience.contextKey().ownerId(), experience.contextKey().domainId(), experience.contextKey().agentId(), experience.experienceId(), revision,
                evidenceId, key(source.contextKey()), source.kind().name(), source.sourceId(), source.sourceEventId(), source.sourceSequence(),
                source.version().revision(), source.version().contentHash(), source.version().schemaVersion(),
                dependency.grant() == null ? null : dependency.grant().grantId(), dependency.grant() == null ? null : dependency.grant().revision());
    }

    private MemoryExperience revise(MemoryExperience current, long revision, MemoryState state, double activation, String reason,
            List<MemoryExperience.Evidence> evidence, double confidence, Instant lastObservedAt, Instant lastValidatedAt) {
        return build(current.experienceId(), current.contextKey(), current.association(), revision, current.scenario(), current.applicability(),
                current.conclusion(), state, reason, current.version().revision(), confidence, activation, lastObservedAt, lastValidatedAt, evidence);
    }

    private MemoryExperience build(String id, MemoryContextKey context, MemoryAssociation association, long revision,
            String scenario, String applicability, String conclusion, MemoryState state, String reason, Long supersedes,
            double confidence, double activation, Instant lastObserved, Instant lastValidated, List<MemoryExperience.Evidence> evidence) {
        var placeholder = new MemoryRevision(revision, "0".repeat(64), SCHEMA_VERSION);
        var draft = new MemoryExperience(id, context, association, placeholder, scenario, applicability, conclusion, state, reason,
                supersedes, confidence, activation, lastObserved, lastValidated, ASSESSMENT_VERSION, evidence);
        var version = new MemoryRevision(revision, MemoryCanonicalHash.experienceHash(draft), SCHEMA_VERSION);
        return new MemoryExperience(id, context, association, version, scenario, applicability, conclusion, state, reason,
                supersedes, confidence, activation, lastObserved, lastValidated, ASSESSMENT_VERSION, evidence);
    }

    private Assessment assess(List<MemoryExperience.Evidence> evidence, MemoryExperience.Evidence latest, MemoryState existingState) {
        double positive = 0;
        double negative = 0;
        for (var item : evidence) {
            if (!item.verified()) continue;
            if (item.kind() == MemoryExperience.EvidenceKind.BUSINESS_OUTCOME) {
                if (item.outcome() == MemoryExperience.Outcome.SUCCEEDED) positive++;
                else if (item.outcome() == MemoryExperience.Outcome.FAILED) negative++;
                else if (item.outcome() == MemoryExperience.Outcome.PARTIAL) { positive += .5; negative += .5; }
            } else if (item.kind() == MemoryExperience.EvidenceKind.USER_FEEDBACK) {
                if (item.feedback() == MemoryExperience.Feedback.POSITIVE) positive++;
                else if (item.feedback() == MemoryExperience.Feedback.NEGATIVE) negative++;
            }
        }
        double confidence = unit(.5 + .5 * (positive - negative) / (positive + negative + 1));
        boolean explicitNegativeFeedback = latest.kind() == MemoryExperience.EvidenceKind.USER_FEEDBACK
                && latest.feedback() == MemoryExperience.Feedback.NEGATIVE;
        MemoryState state = existingState == MemoryState.INACTIVE || explicitNegativeFeedback ? MemoryState.INACTIVE : MemoryState.ACTIVE;
        double activation = state == MemoryState.INACTIVE ? 0 : unit(.8 + .1 * positive - .2 * negative);
        return new Assessment(confidence, activation, state);
    }

    private void fail(String contextId, String evidenceId, String leaseOwner, int attempt, RuntimeException exception) {
        transaction(() -> {
            var job = store.one("SELECT state,lease_owner,attempts FROM memory_processing_job WHERE job_id=? FOR UPDATE", jobId(contextId, evidenceId));
            if (job != null && "PROCESSING".equals(job.get("state")) && leaseOwner.equals(job.get("lease_owner")) && attempt == number(job, "attempts")) {
                String error = error(exception);
                boolean terminal = attempt >= properties.getMaxAttempts() || Set.of("FORBIDDEN", "WITHDRAWN", "DELETED", "INVALID_EXTRACTION",
                        "VERSION_CONFLICT", "CONTEXT_BUDGET_EXCEEDED", "NOT_FOUND").contains(error);
                store.jdbc.update("UPDATE memory_processing_job SET state=?,last_error=?,available_at=?,lease_owner=NULL,lease_until=NULL WHERE job_id=?",
                        terminal ? "FAILED" : "RETRY", error, now().plusSeconds(properties.getRetrySeconds()), jobId(contextId, evidenceId));
                store.jdbc.update("UPDATE memory_experience_event SET updated_at=? WHERE context_id=? AND evidence_id=?", now(), contextId, evidenceId);
            }
            return null;
        });
    }

    private void invalidateSelections(String owner, String domain, String agent) {
        store.jdbc.update("UPDATE memory_experience_selection SET selection_json=NULL,updated_at=? WHERE context_id IN "
                        + "(SELECT context_id FROM memory_conversation WHERE owner_id=? AND domain_id=? AND agent_id=?)",
                now(), owner, domain, agent);
    }

    void sourceWithdrawn(MemoryContextKey sourceContext, String sourceEventId, boolean delete) {
        requireTransaction();
        String state = delete ? "DELETED" : "WITHDRAWN";
        var affected = store.jdbc.query("SELECT DISTINCT owner_id,domain_id,agent_id,experience_id FROM memory_experience_evidence_source "
                        + "WHERE source_context_id=? AND source_event_id=? AND state IN ('ACTIVE','WITHDRAWN')",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}, key(sourceContext), sourceEventId);
        store.jdbc.update("UPDATE memory_experience_evidence_source SET state=? WHERE source_context_id=? AND source_event_id=? AND state<>'DELETED'",
                state, key(sourceContext), sourceEventId);
        store.jdbc.update("UPDATE memory_experience_event SET source_state=?,request_json=CASE WHEN ?='DELETED' THEN NULL ELSE request_json END,updated_at=? "
                        + "WHERE source_context_id=? AND source_event_id=? AND source_state<>'DELETED'",
                state, state, now(), key(sourceContext), sourceEventId);
        var events = store.jdbc.query("SELECT context_id,evidence_id FROM memory_experience_event WHERE source_context_id=? AND source_event_id=?",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, key(sourceContext), sourceEventId);
        for (var event : events) {
            store.jdbc.update("UPDATE memory_processing_job SET state='CANCELLED',last_error=NULL,lease_owner=NULL,lease_until=NULL "
                            + "WHERE job_id=? AND state IN ('PENDING','RETRY','PROCESSING')",
                    jobId(event[0], event[1]));
        }
        for (var ids : affected) {
            if (delete) {
                store.jdbc.update("UPDATE memory_experience SET state='DELETED',confidence=NULL,activation=NULL,last_observed_at=NULL,last_validated_at=NULL,"
                        + "lifecycle_revision=lifecycle_revision+1,updated_at=? WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=? AND state<>'DELETED'",
                        now(), ids[0], ids[1], ids[2], ids[3]);
                store.jdbc.update("UPDATE memory_experience_revision SET payload_json=NULL WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=?",
                        ids[0], ids[1], ids[2], ids[3]);
                store.jdbc.update("UPDATE memory_experience_evidence SET evidence_kind=NULL,outcome=NULL,feedback=NULL,verified=NULL,occurred_at=NULL,observed_at=NULL,state='DELETED' "
                                + "WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=?",
                        ids[0], ids[1], ids[2], ids[3]);
                store.jdbc.update("UPDATE memory_experience_evidence_source SET state='DELETED' WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=?",
                        ids[0], ids[1], ids[2], ids[3]);
            } else {
                store.jdbc.update("UPDATE memory_experience SET state='WITHDRAWN',activation=0,lifecycle_revision=lifecycle_revision+1,updated_at=? "
                                + "WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=? AND state IN ('ACTIVE','INACTIVE')",
                        now(), ids[0], ids[1], ids[2], ids[3]);
            }
            invalidateSelections(ids[0], ids[1], ids[2]);
        }
    }

    private void requirePublisher(String callerId, MemoryContextKey context) {
        var caller = callers.getTrustedCallers().get(callerId);
        require(caller != null && caller.isExperienceOutcomePublisher()
                && caller.allows(context.domainId(), context.agentId()), MemoryErrorCode.FORBIDDEN);
    }

    private void requireOccurredAt(Instant occurredAt) {
        require(!occurredAt.isAfter(nowInstant().plusSeconds(300)), MemoryErrorCode.INVALID_ARGUMENT);
    }

    private static void requireSafeText(String value) {
        require(!CREDENTIAL.matcher(value).find(), MemoryErrorCode.INVALID_EXTRACTION);
    }

    private void verifyRevision(ExperienceView current, long expected, boolean allowCreate) {
        if (current == null) {
            require(allowCreate && expected == 0, MemoryErrorCode.NOT_FOUND);
            return;
        }
        require(current.experience().version().revision() == expected, MemoryErrorCode.VERSION_CONFLICT);
        require(current.experience().state() == MemoryState.ACTIVE || current.experience().state() == MemoryState.INACTIVE,
                MemoryErrorCode.WITHDRAWN);
    }

    private void verifyRevision(MemoryExperience current, long expected, boolean allowCreate) {
        verifyRevision(current == null ? null : new ExperienceView(current, Map.of()), expected, allowCreate);
    }

    private static MemoryErrorCode sourceStateError(Map<String, Object> event) {
        return "DELETED".equals(event.get("source_state")) ? MemoryErrorCode.DELETED : MemoryErrorCode.WITHDRAWN;
    }

    private static String error(RuntimeException exception) {
        return exception instanceof MemoryException memory ? memory.code().name() : MemoryErrorCode.MEMORY_UNAVAILABLE.name();
    }

    private boolean samePrivateScope(MemoryContextKey a, MemoryContextKey b) {
        return a.ownerId().equals(b.ownerId()) && a.domainId().equals(b.domainId()) && a.agentId().equals(b.agentId());
    }

    private static boolean positive(MemoryExperience.Evidence evidence) {
        return evidence.kind() == MemoryExperience.EvidenceKind.BUSINESS_OUTCOME
                && evidence.outcome() == MemoryExperience.Outcome.SUCCEEDED
                || evidence.kind() == MemoryExperience.EvidenceKind.USER_FEEDBACK
                && evidence.feedback() == MemoryExperience.Feedback.POSITIVE;
    }

    private long owner(MemoryContextKey context) {
        try {
            long value = Long.parseLong(context.ownerId());
            require(value > 0 && Long.toString(value).equals(context.ownerId()), MemoryErrorCode.FORBIDDEN);
            return value;
        } catch (NumberFormatException e) { throw new MemoryException(MemoryErrorCode.FORBIDDEN); }
    }

    private void requireEnabled() { require(properties.isEnabled(), MemoryErrorCode.MEMORY_UNAVAILABLE); }
    private static double unit(double value) { return Math.max(0, Math.min(1, value)); }
    private Instant later(Instant a, Instant b) { return a == null || b.isAfter(a) ? b : a; }
    private Instant nowInstant() { return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(nowInstant(), ZoneOffset.UTC); }
    private static LocalDateTime sqlTime(Instant value) { return LocalDateTime.ofInstant(value.truncatedTo(java.time.temporal.ChronoUnit.MICROS), ZoneOffset.UTC); }
    private static String jobId(String contextId, String eventId) { return MemoryCanonicalHash.contentHash(List.of(contextId, eventId, "ASSESS_EXPERIENCE")); }
    private static long number(Map<String, Object> row, String field) { return MemoryArchiveStore.number(row, field); }
    private static <T> T transactionResult(T value) { return value; }
    private <T> T transaction(Supplier<T> action) { return tx.execute(status -> action.get()); }
    private record ExperienceView(MemoryExperience experience, Map<String, Object> header) { }
    private record Assessment(double confidence, double activation, MemoryState state) { }
}
