package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;

import static com.bkanent.memory.service.MemoryArchiveStore.*;

/** Reliable archive lifecycle. It never executes tools, calls a model, or reads v1 memory_json. */
@Service
public class MemoryArchiveService {
    private final MemoryArchiveStore store;
    private final MemoryV2RequestSecurity security;
    private final MemorySourceAuthorizer sources;
    private final TransactionTemplate tx;

    public MemoryArchiveService(MemoryArchiveStore store, MemoryV2RequestSecurity security,
                                MemorySourceAuthorizer sources, PlatformTransactionManager transactionManager) {
        this.store = store;
        this.security = security;
        this.sources = sources;
        tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(5);
    }

    public MemoryReceivePermitResponse requestReceivePermit(MemoryReceivePermitRequest request, MemoryRequestProof proof) {
        security.authorize(request, proof, MemoryV2Contract.OPERATION_RECEIVE_PERMIT);
        return transaction(() -> {
            store.conversation(request.contextKey());
            requireWriter(request.contextKey(), request.envelope().caller(), request.writer());
            String contextId = key(request.contextKey());
            var existing = store.one("SELECT * FROM memory_receive_permit WHERE context_id=? AND source_caller=? AND request_id=?",
                    contextId, request.envelope().caller(), request.envelope().requestId());
            List<MemoryReceivePermitResponse.EventAcceptance> accepted = new ArrayList<>();
            request.events().forEach(event -> accepted.add(new MemoryReceivePermitResponse.EventAcceptance(event.eventId(), true, null)));
            request.resultAdmissions().forEach(result -> accepted.add(new MemoryReceivePermitResponse.EventAcceptance(result.resultEventId(), true, null)));
            if (existing != null) {
                require(request.envelope().bodyHash().equals(existing.get("body_hash")), MemoryErrorCode.VERSION_CONFLICT);
                require(utcInstant(existing.get("expires_at")).isAfter(Instant.now()), MemoryErrorCode.AUTHENTICATION_EXPIRED);
                return new MemoryReceivePermitResponse((String) existing.get("permit_id"), accepted);
            }
            for (var event : request.events()) {
                require(event.purpose().equals("conversation-archive"), MemoryErrorCode.FORBIDDEN);
                var previous = store.one("SELECT * FROM memory_message WHERE context_id=? AND source_event_id=?", contextId, event.eventId());
                if (previous != null) {
                    requireActive(previous);
                    require(event.contentHash().equals(previous.get("content_hash")), MemoryErrorCode.VERSION_CONFLICT);
                }
            }
            for (var admission : request.resultAdmissions()) {
                acceptedInvocation(request.contextKey(), request.envelope().caller(), admission);
                String admissionKey = MemoryCanonicalHash.contentHash(List.of(request.contextKey(), admission.acceptedSource(), admission.callId()));
                String admissionHash = MemoryCanonicalHash.contentHash(admission);
                var old = store.one("SELECT * FROM memory_result_admission WHERE admission_key=?", admissionKey);
                if (old == null) {
                    require(store.one("SELECT * FROM memory_result_admission WHERE context_id=? AND result_event_id=?", contextId,
                            admission.resultEventId()) == null, MemoryErrorCode.VERSION_CONFLICT);
                    store.jdbc.update("INSERT INTO memory_result_admission(admission_key,admission_hash,context_id,result_event_id) VALUES(?,?,?,?)",
                            admissionKey, admissionHash, contextId, admission.resultEventId());
                } else {
                    require(admissionHash.equals(old.get("admission_hash")), MemoryErrorCode.VERSION_CONFLICT);
                }
            }
            String id = UUID.randomUUID().toString();
            store.jdbc.update("INSERT INTO memory_receive_permit(permit_id,context_id,source_caller,request_id,body_hash,expires_at) VALUES(?,?,?,?,?,?)",
                    id, contextId, request.envelope().caller(), request.envelope().requestId(), request.envelope().bodyHash(),
                    LocalDateTime.ofInstant(Instant.now().plusSeconds(7 * 86400), ZoneOffset.UTC));
            for (var event : request.events()) {
                store.jdbc.update("INSERT INTO memory_receive_event(permit_id,event_id,content_hash,purpose) VALUES(?,?,?,?)",
                        id, event.eventId(), event.contentHash(), event.purpose());
            }
            for (var admission : request.resultAdmissions()) {
                store.jdbc.update("INSERT INTO memory_receive_event(permit_id,event_id,purpose,admission_json) VALUES(?,?,?,?)",
                        id, admission.resultEventId(), "accepted-invocation-result", store.encode(admission));
            }
            MemoryV2RequestSecurity.requireUnexpired(proof);
            return new MemoryReceivePermitResponse(id, accepted);
        });
    }

    public MemoryDeliveryResponse deliverMessages(MemoryDeliveryRequest request, MemoryRequestProof proof) {
        security.authorizeDelivery(request, proof);
        // Bound the whole RPC, including nested structured tool results, before SQL writes.
        store.encode(request);
        return transaction(() -> {
            var conversation = store.conversation(request.contextKey());
            String contextId = key(request.contextKey());
            var permit = store.one("SELECT * FROM memory_receive_permit WHERE permit_id=? FOR UPDATE", request.permitId());
            require(permit != null && contextId.equals(permit.get("context_id"))
                    && request.envelope().caller().equals(permit.get("source_caller")), MemoryErrorCode.FORBIDDEN);
            require(utcInstant(permit.get("expires_at")).isAfter(Instant.now()), MemoryErrorCode.AUTHENTICATION_EXPIRED);
            List<MemoryDeliveryResponse.DeliveryResult> receipts = new ArrayList<>();
            Set<String> events = new HashSet<>();
            long last = number(conversation, "last_sequence");
            boolean changed = false;
            for (MemoryMessage message : request.messages()) {
                require(request.contextKey().equals(message.contextKey()) && request.association().equals(message.association()), MemoryErrorCode.FORBIDDEN);
                String event = message.source().sourceEventId();
                require(events.add(event), MemoryErrorCode.INVALID_ARGUMENT);
                String hash = MemoryCanonicalHash.messageHash(message);
                require(hash.equals(message.source().version().contentHash()), MemoryErrorCode.VERSION_CONFLICT);
                var allowance = store.one("SELECT * FROM memory_receive_event WHERE permit_id=? AND event_id=? FOR UPDATE", request.permitId(), event);
                require(allowance != null, MemoryErrorCode.FORBIDDEN);
                if (allowance.get("content_hash") != null) {
                    require(hash.equals(allowance.get("content_hash")), MemoryErrorCode.VERSION_CONFLICT);
                }
                if (allowance.get("admission_json") != null) {
                    var admission = store.decode((String) allowance.get("admission_json"), MemoryResultAdmission.class);
                    var accepted = acceptedInvocation(request.contextKey(), request.envelope().caller(), admission);
                    requireResult(message, admission, accepted);
                }
                var prior = store.one("SELECT * FROM memory_message WHERE context_id=? AND source_event_id=?", contextId, event);
                if (prior != null) {
                    requireActive(prior);
                    require(hash.equals(prior.get("content_hash")) && message.messageId().equals(prior.get("message_id"))
                            && message.sequence() == number(prior, "sequence_no"), MemoryErrorCode.VERSION_CONFLICT);
                } else {
                    require(message.sequence() == last + 1, MemoryErrorCode.VERSION_CONFLICT);
                    require(store.one("SELECT message_id FROM memory_message WHERE context_id=? AND message_id=?", contextId, message.messageId()) == null,
                            MemoryErrorCode.VERSION_CONFLICT);
                    var dependencies = java.util.stream.Stream.concat(message.dependencies().stream(),
                            message.toolResponses().stream().flatMap(response -> response.dependencies().stream())).distinct().toList();
                    sources.requireCurrent(request.contextKey(), dependencies);
                    store.jdbc.update("INSERT INTO memory_message(context_id,sequence_no,message_id,source_event_id,content_hash,content_revision,"
                                    + "schema_version,source_caller,payload_json) VALUES(?,?,?,?,?,?,?,?,?)",
                            contextId, message.sequence(), message.messageId(), event, hash, message.source().version().revision(),
                            message.source().version().schemaVersion(), request.envelope().caller(), store.encode(message));
                    store.jdbc.update("INSERT INTO memory_message_observation(context_id,source_event_id,accepted_at) VALUES(?,?,?)",
                            contextId, event, LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
                    store.dependencies(contextId, "MESSAGE", message.messageId(), message.source().version().revision(), dependencies);
                    store.enqueue(contextId, event, "ASSESS_MESSAGE");
                    last = message.sequence();
                    changed = true;
                }
                var receipt = new MemoryDeliveryResponse.DeliveryResult(message.sequence(), true, null);
                store.jdbc.update("UPDATE memory_receive_event SET content_hash=?,receipt_json=? WHERE permit_id=? AND event_id=?",
                        hash, store.encode(receipt), request.permitId(), event);
                receipts.add(receipt);
            }
            if (changed) store.jdbc.update("UPDATE memory_conversation SET last_sequence=?,revision=revision+1 WHERE context_id=?", last, contextId);
            MemoryV2RequestSecurity.requireUnexpired(proof);
            return new MemoryDeliveryResponse(request.permitId(), store.version(store.conversation(request.contextKey())), receipts);
        });
    }

    private MemoryMessage acceptedInvocation(MemoryContextKey context, String caller, MemoryResultAdmission admission) {
        require(context.equals(admission.acceptedSource().contextKey()), MemoryErrorCode.FORBIDDEN);
        sources.requireCurrent(context, List.of(new MemorySourceDependency(admission.acceptedSource(), null)));
        var row = store.one("SELECT * FROM memory_message WHERE context_id=? AND source_event_id=?", key(context), admission.acceptedSource().sourceEventId());
        require(row != null && caller.equals(row.get("source_caller")), MemoryErrorCode.FORBIDDEN);
        MemoryMessage accepted = store.decode((String) row.get("payload_json"), MemoryMessage.class);
        require(accepted.role() == MemoryMessage.Role.TOOL && accepted.toolResponses().stream().anyMatch(response ->
                response.callId().equals(admission.callId()) && response.state() == MemoryToolResponse.ExecutionState.ACCEPTED), MemoryErrorCode.FORBIDDEN);
        require(admission.resultSequence() > accepted.sequence(), MemoryErrorCode.FORBIDDEN);
        return accepted;
    }

    private void requireResult(MemoryMessage message, MemoryResultAdmission admission, MemoryMessage accepted) {
        require(message.messageId().equals(admission.resultMessageId()) && message.sequence() == admission.resultSequence()
                && message.association().equals(accepted.association()) && message.role() == MemoryMessage.Role.TOOL
                && message.toolResponses().size() == 1 && message.text() == null, MemoryErrorCode.FORBIDDEN);
        var result = message.toolResponses().get(0);
        var invocation = accepted.toolResponses().stream().filter(response -> response.callId().equals(admission.callId())).findFirst().orElseThrow();
        require(result.callId().equals(invocation.callId()) && result.toolName().equals(invocation.toolName())
                && Set.of(MemoryToolResponse.ExecutionState.SUCCEEDED, MemoryToolResponse.ExecutionState.FAILED,
                        MemoryToolResponse.ExecutionState.PARTIAL, MemoryToolResponse.ExecutionState.OUTCOME_UNKNOWN).contains(result.state()),
                MemoryErrorCode.FORBIDDEN);
    }

    public MemoryWorkingSetResponse readWorkingSet(MemoryWorkingSetRequest request, MemoryRequestProof proof) {
        security.authorize(request, proof, MemoryV2Contract.OPERATION_WORKING_SET);
        // An authorization RPC never runs while archive/source rows are locked.
        var snapshot = transaction(() -> readSnapshot(request.contextKey()));
        security.revalidate(request, proof, Long.parseLong(request.contextKey().ownerId()), MemoryV2Contract.OPERATION_WORKING_SET);
        return transaction(() -> {
            var current = readSnapshot(request.contextKey());
            require(snapshot.equals(current), MemoryErrorCode.VERSION_CONFLICT);
            MemoryV2RequestSecurity.requireUnexpired(proof);
            return current;
        });
    }

    private MemoryWorkingSetResponse readSnapshot(MemoryContextKey context) {
        var conversation = store.conversation(context);
        String id = key(context);
        List<MemorySummary> summaries = store.jdbc.query("SELECT payload_json FROM memory_summary WHERE context_id=? AND state='ACTIVE' "
                        + "ORDER BY covered_through DESC LIMIT 1", (rs, n) -> store.decode(rs.getString(1), MemorySummary.class), id);
        long covered = 0;
        if (!summaries.isEmpty()) {
            var summary = summaries.get(0);
            try {
                sources.requireCurrent(context, summary.dependencies());
                require(MemoryCanonicalHash.summaryHash(summary).equals(summary.version().contentHash()), MemoryErrorCode.MEMORY_UNAVAILABLE);
                covered = summary.coveredRange().throughSequence();
            } catch (MemoryException exception) {
                if (!unusableSource(exception)) throw exception;
                summaries = List.of();
            }
        }
        var messages = store.messages(id, covered + 1, number(conversation, "last_sequence"));
        require(messages.size() <= 500, MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
        List<MemoryMessage> readable = new ArrayList<>();
        for (var message : messages) {
            try {
                sources.requireCurrent(context, List.of(new MemorySourceDependency(message.source(), null)));
                readable.add(message);
            } catch (MemoryException exception) {
                if (!unusableSource(exception)) throw exception;
            }
        }
        var matters = store.jdbc.query("SELECT payload_json FROM memory_matter WHERE context_id=? AND state IN ('ACTIVE','CLOSED','REOPENED') ORDER BY matter_id LIMIT 501",
                (rs, n) -> store.decode(rs.getString(1), MemoryMatter.class), id);
        require(matters.size() <= 500, MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
        for (var matter : matters) sources.requireCurrent(context, matter.dependencies());
        var result = new MemoryWorkingSetResponse(context, store.version(conversation), readable, summaries, matters);
        boundRead(result); // hard bound; no silent tail truncation
        return result;
    }

    public MemorySummaryResponse submitSummary(MemorySummaryRequest request, MemoryRequestProof proof) {
        security.authorize(request, proof, MemoryV2Contract.OPERATION_SUBMIT_SUMMARY);
        var summary = request.summary();
        require(summary.runVersion() == null, MemoryErrorCode.VERSION_CONFLICT); // run adoption belongs to its checkpoint owner
        require(MemoryCanonicalHash.summaryHash(summary).equals(summary.version().contentHash()), MemoryErrorCode.VERSION_CONFLICT);
        for (var entity : summary.entities()) require(MemoryCanonicalHash.entityHash(entity).equals(entity.version().contentHash()), MemoryErrorCode.VERSION_CONFLICT);
        store.encode(summary);
        return transaction(() -> {
            var conversation = store.conversation(request.contextKey());
            String id = key(request.contextKey());
            requireWriter(request.contextKey(), request.envelope().caller(), request.writer());
            var old = store.one("SELECT * FROM memory_summary WHERE context_id=? AND summary_id=? AND content_revision=?",
                    id, summary.summaryId(), summary.version().revision());
            if (old != null) {
                requireActive(old);
                require(summary.version().contentHash().equals(old.get("content_hash")), MemoryErrorCode.VERSION_CONFLICT);
                sources.requireCurrent(request.contextKey(), summary.dependencies());
                return store.decode((String) old.get("receipt_json"), MemorySummaryResponse.class);
            }
            var previous = store.one("SELECT MAX(content_revision) AS maximum FROM memory_summary WHERE context_id=? AND summary_id=?", id, summary.summaryId());
            require(previous.get("maximum") == null || summary.version().revision() > ((Number) previous.get("maximum")).longValue(), MemoryErrorCode.VERSION_CONFLICT);
            require(summary.expectedConversationRevision() == number(conversation, "revision"), MemoryErrorCode.VERSION_CONFLICT);
            require(summary.coveredRange().fromSequence() == 1 && summary.coveredRange().throughSequence() <= number(conversation, "last_sequence")
                    && summary.coveredRange().throughSequence() > number(conversation, "covered_through"), MemoryErrorCode.ARCHIVE_NOT_CONFIRMED);
            var messages = store.messages(id, 1, summary.coveredRange().throughSequence());
            require(messages.size() <= 500 && messages.size() == summary.coveredRange().throughSequence(), MemoryErrorCode.ARCHIVE_NOT_CONFIRMED);
            var evidence = messages.stream().map(message -> new MemorySourceDependency(message.source(), null)).toList();
            require(summary.dependencies().containsAll(evidence), MemoryErrorCode.ARCHIVE_NOT_CONFIRMED);
            sources.requireCurrent(request.contextKey(), summary.dependencies());
            requireCompleteToolGroups(messages, summary.toolGroupRanges());
            for (var anchor : summary.toolResults()) {
                require(messages.stream().anyMatch(message -> message.source().equals(anchor.source())
                        && message.toolResponses().stream().anyMatch(response -> response.callId().equals(anchor.callId())
                        && response.toolName().equals(anchor.toolName()) && response.state() == anchor.state()
                        && response.dataForm() == MemoryToolResponse.DataForm.INLINE_COMPLETE)), MemoryErrorCode.ARCHIVE_NOT_CONFIRMED);
            }
            store.jdbc.update("UPDATE memory_summary SET state='INACTIVE' WHERE context_id=? AND state='ACTIVE'", id);
            store.jdbc.update("UPDATE memory_entity_anchor SET state='INACTIVE' WHERE context_id=? AND state='ACTIVE'", id);
            store.jdbc.update("UPDATE memory_conversation SET revision=revision+1,covered_through=? WHERE context_id=?", summary.coveredRange().throughSequence(), id);
            var receipt = new MemorySummaryResponse(summary.summaryId(), true, null, store.version(store.conversation(request.contextKey())));
            store.jdbc.update("INSERT INTO memory_summary(context_id,summary_id,content_revision,content_hash,covered_from,covered_through,payload_json,receipt_json) "
                            + "VALUES(?,?,?,?,?,?,?,?)", id, summary.summaryId(), summary.version().revision(), summary.version().contentHash(),
                    1, summary.coveredRange().throughSequence(), store.encode(summary), store.encode(receipt));
            store.dependencies(id, "SUMMARY", summary.summaryId(), summary.version().revision(), summary.dependencies());
            for (var entity : summary.entities()) {
                var current = store.one("SELECT * FROM memory_entity_anchor WHERE context_id=? AND entity_id=?", id, entity.entityId());
                require(current == null || number(current, "content_revision") < entity.version().revision()
                        || number(current, "content_revision") == entity.version().revision()
                        && store.decode((String) current.get("payload_json"), MemoryEntityAnchor.class).equals(entity), MemoryErrorCode.VERSION_CONFLICT);
                if (current != null) store.jdbc.update("DELETE FROM memory_entity_anchor WHERE context_id=? AND entity_id=?", id, entity.entityId());
                store.jdbc.update("INSERT INTO memory_entity_anchor(context_id,entity_id,content_revision,summary_id,summary_revision,payload_json) VALUES(?,?,?,?,?,?)",
                        id, entity.entityId(), entity.version().revision(), summary.summaryId(), summary.version().revision(), store.encode(entity));
                store.dependencies(id, "ENTITY", entity.entityId(), entity.version().revision(), entity.dependencies());
            }
            store.enqueue(id, MemoryCanonicalHash.contentHash(List.of("summary", summary.summaryId(), summary.version().revision())), "INDEX_SUMMARY");
            MemoryV2RequestSecurity.requireUnexpired(proof);
            return receipt;
        });
    }

    private void requireCompleteToolGroups(List<MemoryMessage> messages, List<MemoryCoveredRange> declared) {
        Map<String, MemoryMessage> calls = new HashMap<>();
        Map<MemoryMessage, List<MemoryMessage>> groups = new LinkedHashMap<>();
        for (var message : messages) {
            for (var call : message.toolCalls()) {
                require(calls.putIfAbsent(call.callId(), message) == null, MemoryErrorCode.INCOMPLETE_TOOL_GROUP);
                groups.computeIfAbsent(message, ignored -> new ArrayList<>());
            }
            for (var response : message.toolResponses()) {
                var assistant = calls.remove(response.callId());
                require(assistant != null && !Set.of(MemoryToolResponse.ExecutionState.WAITING_APPROVAL,
                        MemoryToolResponse.ExecutionState.OUTCOME_UNKNOWN, MemoryToolResponse.ExecutionState.ACCEPTED).contains(response.state()),
                        MemoryErrorCode.INCOMPLETE_TOOL_GROUP);
                var responses = groups.get(assistant);
                if (!responses.contains(message)) responses.add(message);
            }
        }
        require(calls.isEmpty(), MemoryErrorCode.INCOMPLETE_TOOL_GROUP);
        List<MemoryCoveredRange> actual = new ArrayList<>();
        groups.forEach((assistant, responses) -> actual.add(new MemoryToolGroup(assistant, responses).range()));
        require(actual.equals(declared), MemoryErrorCode.INCOMPLETE_TOOL_GROUP);
    }

    public MemoryWriterResponse manageWriter(MemoryWriterRequest request, MemoryRequestProof proof) {
        security.authorize(request, proof, MemoryV2Contract.OPERATION_WRITER);
        return transaction(() -> {
            var conversation = store.conversation(request.contextKey());
            String id = key(request.contextKey());
            long now = System.currentTimeMillis();
            var writer = store.one("SELECT * FROM memory_conversation_writer WHERE context_id=? FOR UPDATE", id);
            long fence = writer == null ? 0 : number(writer, "fence");
            boolean owner = writer != null && request.envelope().caller().equals(writer.get("source_caller"))
                    && request.runId().equals(writer.get("run_id"));
            long expires = now + 120_000;
            if (request.action() == MemoryWriterRequest.Action.ACQUIRE) {
                boolean active = writer != null && number(writer, "expires_at_ms") > now;
                require(!active || owner && request.envelope().requestId().equals(writer.get("acquire_id")), MemoryErrorCode.VERSION_CONFLICT);
                if (!active) fence++;
                if (writer == null) {
                    store.jdbc.update("INSERT INTO memory_conversation_writer(context_id,source_caller,run_id,acquire_id,fence,expires_at_ms) VALUES(?,?,?,?,?,?)",
                            id, request.envelope().caller(), request.runId(), request.envelope().requestId(), fence, expires);
                } else {
                    store.jdbc.update("UPDATE memory_conversation_writer SET source_caller=?,run_id=?,acquire_id=?,fence=?,expires_at_ms=? WHERE context_id=?",
                            request.envelope().caller(), request.runId(), request.envelope().requestId(), fence, expires, id);
                }
            } else {
                require(owner && request.fence() == fence && number(writer, "expires_at_ms") > now, MemoryErrorCode.VERSION_CONFLICT);
                if (request.action() == MemoryWriterRequest.Action.RELEASE) expires = 0;
                store.jdbc.update("UPDATE memory_conversation_writer SET expires_at_ms=? WHERE context_id=?", expires, id);
            }
            MemoryV2RequestSecurity.requireUnexpired(proof);
            return new MemoryWriterResponse(new MemoryWriteAuthority(request.runId(), fence), expires, store.version(conversation));
        });
    }

    private void requireWriter(MemoryContextKey context, String caller, MemoryWriteAuthority authority) {
        var writer = store.one("SELECT * FROM memory_conversation_writer WHERE context_id=? FOR UPDATE", key(context));
        // Contexts are enrolled on first acquire. Enrolled contexts never fall back to unfenced writes.
        if (writer == null) {
            require(authority == null, MemoryErrorCode.VERSION_CONFLICT);
            return;
        }
        require(authority != null && caller.equals(writer.get("source_caller")) && authority.runId().equals(writer.get("run_id"))
                && authority.fence() == number(writer, "fence") && number(writer, "expires_at_ms") > System.currentTimeMillis(), MemoryErrorCode.VERSION_CONFLICT);
    }

    public MemoryBoundaryResponse checkBoundary(MemoryBoundaryRequest request, MemoryRequestProof proof) {
        security.authorize(request, proof, MemoryV2Contract.OPERATION_BOUNDARY);
        transaction(() -> {
            store.conversation(request.contextKey());
            sources.requireCurrentMetadata(request.contextKey(), request.dependencies());
            return null;
        });
        security.revalidate(request, proof, Long.parseLong(request.contextKey().ownerId()), MemoryV2Contract.OPERATION_BOUNDARY);
        return transaction(() -> {
            var conversation = store.conversation(request.contextKey());
            sources.requireCurrentMetadata(request.contextKey(), request.dependencies());
            MemoryV2RequestSecurity.requireUnexpired(proof);
            var current = store.version(conversation);
            return new MemoryBoundaryResponse(current, current.equals(request.adoptedVersion()));
        });
    }

    public MemoryWithdrawalResponse withdrawSource(MemoryWithdrawalRequest request, MemoryRequestProof proof) {
        security.authorize(request, proof, MemoryV2Contract.OPERATION_WITHDRAW);
        return transaction(() -> {
            store.conversation(request.contextKey());
            String id = key(request.contextKey());
            var row = store.one("SELECT * FROM memory_message WHERE context_id=? AND source_event_id=? FOR UPDATE", id, request.sourceEventId());
            require(row != null, MemoryErrorCode.NOT_FOUND);
            boolean delete = request.delete() || "DELETION_PENDING".equals(row.get("state"));
            String state = delete ? "DELETION_PENDING" : "WITHDRAWN";
            store.jdbc.update("UPDATE memory_message SET state=?,payload_json=CASE WHEN ? THEN NULL ELSE payload_json END WHERE context_id=? AND source_event_id=?",
                    state, delete, id, request.sourceEventId());
            // Preference blocks span this private agent's conversations. Revoke every dependent block before any old page can be reused.
            var affectedPreferences = store.jdbc.queryForList("SELECT DISTINCT target_id FROM memory_source_dependency WHERE source_context_id=? "
                    + "AND source_event_id=? AND target_kind='PREFERENCE'", String.class, id, request.sourceEventId());
            for (String blockId : affectedPreferences) {
                store.jdbc.update("UPDATE memory_preference_block SET state=?,authorization_revision=authorization_revision+1 WHERE block_id=?", state, blockId);
                if (delete) {
                    store.jdbc.update("UPDATE memory_preference_revision SET snapshot_json=NULL WHERE block_id=?", blockId);
                    store.jdbc.update("UPDATE memory_preference_transition SET payload_json=NULL WHERE block_id=?", blockId);
                }
            }
            // Invalidate the whole private derived set; rebuild from permitted evidence later.
            store.jdbc.update("UPDATE memory_summary SET state=?,payload_json=CASE WHEN ? THEN NULL ELSE payload_json END WHERE context_id=?", state, delete, id);
            store.jdbc.update("UPDATE memory_entity_anchor SET state=?,payload_json=CASE WHEN ? THEN NULL ELSE payload_json END WHERE context_id=?", state, delete, id);
            store.jdbc.update("UPDATE memory_matter SET state=?,payload_json=CASE WHEN ? THEN NULL ELSE payload_json END WHERE context_id=?", state, delete, id);
            // All exports of this source conversation stop publishing immediately. External copies require cleanup acknowledgements.
            store.jdbc.update("UPDATE memory_shared_export SET state=?,lifecycle_revision=lifecycle_revision+1,"
                            + "payload_json=CASE WHEN ? THEN NULL ELSE payload_json END WHERE owner_id=? AND source_domain_id=? AND source_agent_id=? AND source_conversation_id=?",
                    state, delete, request.contextKey().ownerId(), request.contextKey().domainId(), request.contextKey().agentId(), request.contextKey().conversationId());
            store.jdbc.update("UPDATE memory_processing_job SET state='CANCELLED',lease_owner=NULL,lease_until=NULL WHERE context_id=? AND job_kind<>'PRIVACY_CLEANUP'", id);
            if (delete) store.jdbc.update("UPDATE memory_preference_evaluation SET result_json=NULL WHERE job_id IN "
                    + "(SELECT job_id FROM memory_processing_job WHERE context_id=?)", id);
            store.enqueue(id, request.sourceEventId(), "PRIVACY_CLEANUP");
            store.jdbc.update("UPDATE memory_conversation SET revision=revision+1,covered_through=0 WHERE context_id=?", id);
            MemoryV2RequestSecurity.requireUnexpired(proof);
            return new MemoryWithdrawalResponse(request.sourceEventId(), MemoryState.valueOf(state), store.version(store.conversation(request.contextKey())));
        });
    }

    public MemoryEntityQueryResponse queryEntities(MemoryEntityQueryRequest request, MemoryRequestProof proof) {
        security.authorize(request, proof, MemoryV2Contract.OPERATION_QUERY_ENTITIES);
        return checkedRead(request, proof, MemoryV2Contract.OPERATION_QUERY_ENTITIES, () -> {
            var conversation = store.conversation(request.contextKey());
            long revision = number(conversation, "revision");
            String after = cursor(request.cursor(), revision, key(request.contextKey()), "memory_entity_anchor", "entity_id");
            var entities = store.jdbc.query("SELECT payload_json FROM memory_entity_anchor WHERE context_id=? AND state='ACTIVE' AND entity_id>? ORDER BY entity_id LIMIT ?",
                    (rs, n) -> store.decode(rs.getString(1), MemoryEntityAnchor.class), key(request.contextKey()), after, request.limit() + 1);
            for (var entity : entities) {
                require(entity.contextKey().equals(request.contextKey()) && MemoryCanonicalHash.entityHash(entity).equals(entity.version().contentHash()), MemoryErrorCode.MEMORY_UNAVAILABLE);
                sources.requireCurrent(request.contextKey(), entity.dependencies());
            }
            boolean more = entities.size() > request.limit();
            var page = more ? entities.subList(0, request.limit()) : entities;
            return new MemoryEntityQueryResponse(page, more ? revision + ":" + MemoryCanonicalHash.contentHash(page.get(page.size() - 1).entityId()) : null, more);
        });
    }

    public MemoryMatterQueryResponse queryMatters(MemoryMatterQueryRequest request, MemoryRequestProof proof) {
        security.authorize(request, proof, MemoryV2Contract.OPERATION_QUERY_MATTERS);
        return checkedRead(request, proof, MemoryV2Contract.OPERATION_QUERY_MATTERS, () -> {
            var conversation = store.conversation(request.contextKey());
            long revision = number(conversation, "revision");
            String after = cursor(request.cursor(), revision, key(request.contextKey()), "memory_matter", "matter_id");
            var matters = store.jdbc.query("SELECT payload_json FROM memory_matter WHERE context_id=? AND state IN ('ACTIVE','CLOSED','REOPENED') AND matter_id>? ORDER BY matter_id LIMIT ?",
                    (rs, n) -> store.decode(rs.getString(1), MemoryMatter.class), key(request.contextKey()), after, request.limit() + 1);
            for (var matter : matters) sources.requireCurrent(request.contextKey(), matter.dependencies());
            boolean more = matters.size() > request.limit();
            var page = more ? matters.subList(0, request.limit()) : matters;
            return new MemoryMatterQueryResponse(page, more ? revision + ":" + MemoryCanonicalHash.contentHash(page.get(page.size() - 1).matterId()) : null, more);
        });
    }

    private String cursor(String cursor, long revision, String contextId, String table, String idColumn) {
        if (cursor == null) return "";
        int colon = cursor.indexOf(':');
        require(colon > 0 && cursor.substring(0, colon).equals(Long.toString(revision)) && colon < cursor.length() - 1,
                MemoryErrorCode.CURSOR_INVALID);
        String digest = cursor.substring(colon + 1);
        // Metadata only, bounded by the per-context aggregate budget. No source body is read to resolve a cursor.
        var ids = store.jdbc.queryForList("SELECT " + idColumn + " FROM " + table + " WHERE context_id=? AND state IN ('ACTIVE','CLOSED','REOPENED') LIMIT 501",
                String.class, contextId);
        require(ids.size() <= 500, MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
        return ids.stream().filter(id -> MemoryCanonicalHash.contentHash(id).equals(digest)).findFirst()
                .orElseThrow(() -> new MemoryException(MemoryErrorCode.CURSOR_INVALID));
    }

    private <T> T checkedRead(MemoryV2Request request, MemoryRequestProof proof, String operation, Supplier<T> read) {
        T snapshot = transaction(read);
        security.revalidate(request, proof, Long.parseLong(request.contextKey().ownerId()), operation);
        return transaction(() -> {
            T current = read.get();
            require(snapshot.equals(current), MemoryErrorCode.VERSION_CONFLICT);
            MemoryV2RequestSecurity.requireUnexpired(proof);
            boundRead(current);
            return current;
        });
    }

    private static boolean unusableSource(MemoryException exception) {
        return Set.of(MemoryErrorCode.FORBIDDEN, MemoryErrorCode.WITHDRAWN, MemoryErrorCode.DELETED,
                MemoryErrorCode.VERSION_CONFLICT, MemoryErrorCode.NOT_FOUND, MemoryErrorCode.ARCHIVE_NOT_CONFIRMED).contains(exception.code());
    }

    private void boundRead(Object response) {
        try {
            store.encode(response);
        } catch (MemoryException exception) {
            if (exception.code() == MemoryErrorCode.INVALID_ARGUMENT) throw new MemoryException(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
            throw exception;
        }
    }

    private static void requireActive(Map<String, Object> row) {
        require("ACTIVE".equals(row.get("state")), "DELETION_PENDING".equals(row.get("state")) ? MemoryErrorCode.DELETED : MemoryErrorCode.WITHDRAWN);
    }

    private <T> T transaction(Supplier<T> action) {
        try {
            return tx.execute(status -> action.get());
        } catch (DataAccessException | TransactionException exception) {
            throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
        }
    }
}
