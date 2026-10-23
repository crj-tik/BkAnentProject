package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.memory.config.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import static com.bkanent.memory.service.MemoryArchiveStore.*;

/** Exact accepted-event SERVICE consumer. Model calls never hold SQL locks or impersonate a user. */
@Service
@ConditionalOnProperty(prefix="memory.v2.preference.processing", name="enabled", havingValue="true")
public class PreferenceProcessingWorker {
    private final MemoryArchiveStore store;
    private final MemorySourceAuthorizer sources;
    private final PreferenceHistoryService preferences;
    private final MemoryPreferenceProperties dimensions;
    private final MemoryPreferenceProcessingProperties properties;
    private final MemoryV2SecurityProperties callers;
    private final PreferenceEvaluator evaluator;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final AtomicBoolean running = new AtomicBoolean();
    record Claim(String id, String lease, int attempt) { }
    record Source(MemoryContextKey context, String caller, MemorySourceDependency dependency, Instant acceptedAt) { }
    record Pending(String id, int attempt, Source source, PreferenceEvaluator.Result result) { }
    record Item(Source source, MemoryPreferenceAssessment candidate) { }

    @Autowired
    public PreferenceProcessingWorker(MemoryArchiveStore store, MemorySourceAuthorizer sources, PreferenceHistoryService preferences,
            MemoryPreferenceProperties dimensions, MemoryPreferenceProcessingProperties properties, MemoryV2SecurityProperties callers,
            ObjectProvider<PreferenceEvaluator> evaluator, PlatformTransactionManager manager) {
        this(store,sources,preferences,dimensions,properties,callers,evaluator.getIfAvailable(),manager,Clock.systemUTC());
    }

    public PreferenceProcessingWorker(MemoryArchiveStore store, MemorySourceAuthorizer sources, PreferenceHistoryService preferences,
            MemoryPreferenceProperties dimensions, MemoryPreferenceProcessingProperties properties, MemoryV2SecurityProperties callers,
            PreferenceEvaluator evaluator, PlatformTransactionManager manager) {
        this(store,sources,preferences,dimensions,properties,callers,evaluator,manager,Clock.systemUTC());
    }

    PreferenceProcessingWorker(MemoryArchiveStore store, MemorySourceAuthorizer sources, PreferenceHistoryService preferences,
            MemoryPreferenceProperties dimensions, MemoryPreferenceProcessingProperties properties, MemoryV2SecurityProperties callers,
            PreferenceEvaluator evaluator, PlatformTransactionManager manager, Clock clock) {
        this.store=store; this.sources=sources; this.preferences=preferences; this.dimensions=dimensions;
        this.properties=properties; this.callers=callers; this.evaluator=evaluator;
        this.clock=clock;
        tx=new TransactionTemplate(manager); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(10);
    }

    @Scheduled(fixedDelayString="${memory.v2.preference.processing.poll-ms:5000}", scheduler="memoryPreferenceScheduler")
    public void runOnce() {
        if (evaluator == null || !properties.isEnabled() || !running.compareAndSet(false,true)) return;
        try {
            for (int i=0; i<properties.getBatchSize(); i++) {
                Claim claim=transaction(this::claim);
                if (claim==null) break;
                assess(claim);
            }
            consolidate();
        } finally { running.set(false); }
    }

    Claim claim() {
        var args=new ArrayList<Object>(); String scope=scope(args);
        if (scope.isEmpty()) return null;
        var now=now(); args.add(now); args.add(now);
        var row=store.one("SELECT j.* FROM memory_processing_job j JOIN memory_conversation c ON c.context_id=j.context_id "
                + "WHERE j.job_kind='ASSESS_MESSAGE' AND ("+scope+") AND ((j.state IN ('PENDING','RETRY') AND j.available_at<=?) "
                + "OR (j.state='PROCESSING' AND j.lease_until<=?)) ORDER BY j.available_at,j.job_id LIMIT 1 FOR UPDATE SKIP LOCKED",args.toArray());
        if(row==null) return null;
        String id=(String)row.get("job_id"); int attempts=(int)number(row,"attempts");
        if(attempts>=properties.getMaxAttempts()) {
            store.jdbc.update("UPDATE memory_processing_job SET state='FAILED',last_error='RETRY_EXHAUSTED',lease_owner=NULL,lease_until=NULL WHERE job_id=?",id);
            return new Claim(id,"exhausted",attempts);
        }
        String lease=UUID.randomUUID().toString();
        store.jdbc.update("UPDATE memory_processing_job SET state='PROCESSING',attempts=attempts+1,lease_owner=?,lease_until=? WHERE job_id=?",
                lease,now.plusSeconds(properties.getLeaseSeconds()),id);
        return new Claim(id,lease,attempts+1);
    }

    void assess(Claim claim) {
        if (claim.lease().equals("exhausted")) return;
        try {
            var evidence=transaction(() -> {
                var job=leased(claim); var source=source(job);
                var row=store.one("SELECT payload_json FROM memory_message WHERE context_id=? AND source_event_id=?",key(source.context()),job.get("source_event_id"));
                var message=store.decode((String)row.get("payload_json"),MemoryMessage.class);
                require(message.source().equals(source.dependency().source()) && MemoryCanonicalHash.messageHash(message).equals(message.source().version().contentHash()),MemoryErrorCode.MEMORY_UNAVAILABLE);
                if(message.role()!=MemoryMessage.Role.USER) { finish(claim.id(),"SKIPPED"); return null; }
                require(message.text()!=null && message.text().length()<=16000 && !PreferenceModelEvaluator.containsCredential(message.text()),MemoryErrorCode.INVALID_EXTRACTION);
                return new PreferenceEvaluator.Evidence(message,source.acceptedAt(),Set.copyOf(dimensions.getDimensions()));
            });
            if(evidence==null) return;
            // One exact authorized USER message only; the model cannot request other memory or tools.
            var result=evaluator.evaluate(evidence);
            validate(evidence.message().source(),result,evaluator.version());
            transaction(() -> {
                var job=leased(claim); var source=source(job); // withdrawal/policy checks after the remote call
                require(source.dependency().source().equals(evidence.message().source()),MemoryErrorCode.VERSION_CONFLICT);
                store.jdbc.update("INSERT INTO memory_preference_evaluation(job_id,source_json,result_json,result_hash,extraction_version,assessed_at) VALUES(?,?,?,?,?,?)",
                        claim.id(),store.encode(source.dependency()),store.encode(result),result.hash(),evaluator.version(),now());
                store.jdbc.update("UPDATE memory_processing_job SET state='ASSESSED',lease_owner=NULL,lease_until=NULL,last_error=NULL,available_at=? WHERE job_id=?",now(),claim.id());
                return null;
            });
        } catch(RuntimeException exception) { fail(claim,exception); }
    }

    void consolidate() { consolidate(null); }

    private void consolidate(String onlyId) {
        List<Pending> selected=new ArrayList<>();
        try {
            transaction(() -> {
                var args=new ArrayList<Object>(); String scope=scope(args); if(scope.isEmpty()) return null;
                args.add(now());
                if(onlyId!=null) args.add(onlyId);
                args.add(properties.getBatchSize());
                var rows=store.jdbc.queryForList("SELECT j.* FROM memory_processing_job j JOIN memory_conversation c ON c.context_id=j.context_id "
                        + "WHERE j.job_kind='ASSESS_MESSAGE' AND j.state='ASSESSED' AND ("+scope+") AND j.available_at<=? "
                        + (onlyId==null?"":"AND j.job_id=? ")
                        + "ORDER BY j.job_id LIMIT ? FOR UPDATE SKIP LOCKED",args.toArray());
                for(var row:rows) {
                    String id=(String)row.get("job_id");
                    // Remember the exact attempt before checking sources so permanent authorization failures get a terminal state.
                    var pending=new Pending(id,(int)number(row,"attempts"),null,null); selected.add(pending);
                    var source=source(row);
                    var evaluation=store.one("SELECT * FROM memory_preference_evaluation WHERE job_id=?",id);
                    require(evaluation!=null && source.dependency().equals(store.decode((String)evaluation.get("source_json"),MemorySourceDependency.class)),MemoryErrorCode.MEMORY_UNAVAILABLE);
                    var result=store.decode((String)evaluation.get("result_json"),PreferenceEvaluator.Result.class);
                    require(result.hash().equals(evaluation.get("result_hash")),MemoryErrorCode.MEMORY_UNAVAILABLE);
                    validate(source.dependency().source(),result,(String)evaluation.get("extraction_version"));
                    selected.set(selected.size()-1,new Pending(id,pending.attempt(),source,result));
                }
                // Stable block and source order for the entire microbatch. All dimensions and job receipts commit together.
                List<Item> items=new ArrayList<>();
                for(var pending:selected) for(var candidate:pending.result().candidates()) items.add(new Item(pending.source(),candidate));
                items.sort(Comparator.comparing((Item i) -> new PreferenceBlockKey(i.source().context().ownerId(),i.source().context().ownerId(),
                        MemoryScope.privateScope(i.source().context().domainId(),i.source().context().agentId()),i.candidate().dimensionId()).identityHash())
                        .thenComparing(i -> key(i.source().context())).thenComparingLong(i -> i.candidate().source().source().sourceSequence()));
                if(properties.getMode()==MemoryPreferenceProcessingProperties.Mode.PUBLISH) {
                    for(var item:items) preferences.append(item.source().context(),preferences.linkWithinConversation(item.candidate()),item.source().caller(),null);
                }
                for(var pending:selected) {
                    // Even a shadow receipt cannot be published after the source authority has changed.
                    var job=store.one("SELECT * FROM memory_processing_job WHERE job_id=?",pending.id()); source(job);
                    finish(pending.id(),properties.getMode()==MemoryPreferenceProcessingProperties.Mode.SHADOW?"SHADOWED":"SUCCEEDED");
                }
                return null;
            });
        } catch(RuntimeException exception) {
            // A poison event must not consume the retry allowance of unrelated blocks in the same microbatch.
            if(onlyId==null && selected.size()>1) { selected.forEach(pending -> consolidate(pending.id())); return; }
            for(var pending:selected) transaction(() -> {
                var row=store.one("SELECT state,attempts FROM memory_processing_job WHERE job_id=? FOR UPDATE",pending.id());
                if(row!=null && "ASSESSED".equals(row.get("state")) && number(row,"attempts")==pending.attempt()) {
                    String error=error(exception); int attempt=pending.attempt()+1;
                    store.jdbc.update("UPDATE memory_processing_job SET state=?,attempts=?,last_error=?,available_at=? WHERE job_id=?",
                            terminal(error,attempt)?"FAILED":"ASSESSED",attempt,error,now().plusSeconds(properties.getRetrySeconds()),pending.id());
                }
                return null;
            });
        }
    }

    private Map<String,Object> leased(Claim claim) {
        var row=store.one("SELECT * FROM memory_processing_job WHERE job_id=? FOR UPDATE",claim.id());
        require(row!=null && "PROCESSING".equals(row.get("state")) && claim.lease().equals(row.get("lease_owner"))
                && claim.attempt()==number(row,"attempts") && utcInstant(row.get("lease_until")).isAfter(clock.instant()),MemoryErrorCode.VERSION_CONFLICT);
        return row;
    }

    private Source source(Map<String,Object> job) {
        var c=store.one("SELECT owner_id,domain_id,agent_id,conversation_id FROM memory_conversation WHERE context_id=?",job.get("context_id"));
        require(c!=null,MemoryErrorCode.FORBIDDEN);
        var context=new MemoryContextKey((String)c.get("owner_id"),(String)c.get("domain_id"),(String)c.get("agent_id"),(String)c.get("conversation_id"));
        require(properties.isEnabled() && properties.allows(context.domainId(),context.agentId()),MemoryErrorCode.FORBIDDEN);
        var row=store.one("SELECT message_id,source_caller,source_event_id,sequence_no,content_revision,content_hash,schema_version FROM memory_message WHERE context_id=? AND source_event_id=?",key(context),job.get("source_event_id"));
        require(row!=null,MemoryErrorCode.ARCHIVE_NOT_CONFIRMED);
        String caller=(String)row.get("source_caller"); var trusted=callers.getTrustedCallers().get(caller);
        require(trusted!=null && trusted.getPublicKeys()!=null && !trusted.getPublicKeys().isEmpty()
                && trusted.allows(context.domainId(),context.agentId()),MemoryErrorCode.FORBIDDEN);
        var dependency=new MemorySourceDependency(new MemorySourceRef(MemorySourceRef.Kind.MESSAGE,context,(String)row.get("message_id"),(String)row.get("source_event_id"),
                number(row,"sequence_no"),new MemoryRevision(number(row,"content_revision"),(String)row.get("content_hash"),(String)row.get("schema_version"))),null);
        sources.requireCurrentMetadata(context,List.of(dependency)); // before reading source or derived result body
        var observation=store.one("SELECT accepted_at FROM memory_message_observation WHERE context_id=? AND source_event_id=?",key(context),job.get("source_event_id"));
        require(observation!=null,MemoryErrorCode.MEMORY_UNAVAILABLE);
        return new Source(context,caller,dependency,utcInstant(observation.get("accepted_at")));
    }

    private void validate(MemorySourceRef source, PreferenceEvaluator.Result result, String version) {
        require(result!=null && result.candidates().size()<=8 && result.candidates().stream().map(MemoryPreferenceAssessment::dimensionId).distinct().count()==result.candidates().size(),MemoryErrorCode.INVALID_EXTRACTION);
        require(result.assessment()==PreferenceTransition.Assessment.PREFERENCE || result.candidates().isEmpty(),MemoryErrorCode.INVALID_EXTRACTION);
        for(var candidate:result.candidates()) require(candidate.assessment()==PreferenceTransition.Assessment.PREFERENCE && dimensions.getDimensions().contains(candidate.dimensionId())
                && candidate.source().equals(new MemorySourceDependency(source,null)) && candidate.correctsTransitionIds().isEmpty()
                && candidate.extractionVersion().equals(version),MemoryErrorCode.INVALID_EXTRACTION);
    }
    private void fail(Claim claim,RuntimeException exception) {
        transaction(() -> {
            var row=store.one("SELECT state,lease_owner,attempts FROM memory_processing_job WHERE job_id=? FOR UPDATE",claim.id());
            if(row!=null && "PROCESSING".equals(row.get("state")) && claim.lease().equals(row.get("lease_owner")) && number(row,"attempts")==claim.attempt()) {
                String error=error(exception);
                store.jdbc.update("UPDATE memory_processing_job SET state=?,last_error=?,available_at=?,lease_owner=NULL,lease_until=NULL WHERE job_id=?",
                        terminal(error,claim.attempt())?"FAILED":"RETRY",error,now().plusSeconds(properties.getRetrySeconds()),claim.id());
            }
            return null;
        });
    }
    private boolean terminal(String error,int attempt) {
        return attempt>=properties.getMaxAttempts() || Set.of("FORBIDDEN","WITHDRAWN","DELETED","INVALID_EXTRACTION","CONTEXT_BUDGET_EXCEEDED").contains(error);
    }
    private static String error(RuntimeException exception) { return exception instanceof MemoryException memory?memory.code().name():"MEMORY_UNAVAILABLE"; }
    private void finish(String id,String state) { store.jdbc.update("UPDATE memory_processing_job SET state=?,lease_owner=NULL,lease_until=NULL,last_error=NULL WHERE job_id=?",state,id); }
    private String scope(List<Object> args) {
        var parts=new ArrayList<String>();
        properties.getAllowedAgentsByDomain().forEach((domain,agents) -> agents.stream().sorted().forEach(agent -> {
            parts.add("(c.domain_id=? AND c.agent_id=?)"); args.add(domain); args.add(agent);
        }));
        return String.join(" OR ",parts);
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),ZoneOffset.UTC); }
    private <T>T transaction(Supplier<T> action) { return tx.execute(status -> action.get()); }
}
