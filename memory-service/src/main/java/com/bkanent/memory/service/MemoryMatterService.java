package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.bkanent.memory.service.MemoryArchiveStore.*;

/** Context lifecycle ledger. A model proposal or a tool completion is never user confirmation. */
@Service
public class MemoryMatterService {
    private final MemoryArchiveStore store;
    private final MemoryV2RequestSecurity security;
    private final MemorySourceAuthorizer sources;
    private final TransactionTemplate tx;
    public MemoryMatterService(MemoryArchiveStore store, MemoryV2RequestSecurity security, MemorySourceAuthorizer sources, PlatformTransactionManager manager) {
        this.store=store; this.security=security; this.sources=sources;
        tx=new TransactionTemplate(manager); tx.setTimeout(10); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public MemoryMatterWriteResponse transition(MemoryMatterWriteRequest request, MemoryRequestProof proof) {
        security.authorize(request,proof,MemoryV2Contract.OPERATION_MATTER_WRITE);
        require(request.intent().goal()==null || !CREDENTIAL.matcher(request.intent().goal()).find(),MemoryErrorCode.INVALID_EXTRACTION);
        try { return tx.execute(status -> {
            var context=request.contextKey(); String id=key(context); var conversation=store.conversation(context);
            var writer=store.one("SELECT * FROM memory_conversation_writer WHERE context_id=? FOR UPDATE",id);
            require(writer!=null && request.envelope().caller().equals(writer.get("source_caller")) && request.writer().runId().equals(writer.get("run_id"))
                    && request.writer().fence()==number(writer,"fence") && number(writer,"expires_at_ms")>System.currentTimeMillis(),MemoryErrorCode.VERSION_CONFLICT);
            sources.requireCurrentMetadata(context,List.of(request.evidence()));
            var source=request.evidence().source();
            var row=store.one("SELECT source_caller,payload_json FROM memory_message WHERE context_id=? AND source_event_id=?",id,source.sourceEventId());
            require(row!=null && request.envelope().caller().equals(row.get("source_caller")),MemoryErrorCode.FORBIDDEN);
            var message=store.decode((String)row.get("payload_json"),MemoryMessage.class);
            require(message.role()==MemoryMessage.Role.USER && message.source().equals(source)
                    && MemoryCanonicalHash.messageHash(message).equals(source.version().contentHash()) && request.intent().equals(message.matterIntent()),MemoryErrorCode.FORBIDDEN);
            String hash=MemoryCanonicalHash.contentHash(List.of(request.intent(),request.evidence()));
            String matterId=request.intent().action()==MemoryMatterIntent.Action.START?"matter-"+MemoryCanonicalHash.contentHash(List.of(context,source.sourceEventId())):request.intent().matterId();
            var receipt=store.one("SELECT intent_hash FROM memory_matter_receipt WHERE context_id=? AND source_event_id=?",id,source.sourceEventId());
            if(receipt!=null) {
                require(hash.equals(receipt.get("intent_hash")),MemoryErrorCode.VERSION_CONFLICT);
                var current=store.one("SELECT content_revision,state FROM memory_matter WHERE context_id=? AND matter_id=?",id,matterId);
                require(current!=null && Set.of("ACTIVE","CLOSED","REOPENED").contains(current.get("state")),MemoryErrorCode.WITHDRAWN);
                var locators=store.jdbc.query("SELECT dependency_json FROM memory_source_dependency WHERE target_context_id=? AND target_kind='MATTER' AND target_id=? AND target_revision=? LIMIT 501",
                        (rs,n)->store.decode(rs.getString(1),MemorySourceDependency.class),id,matterId,number(current,"content_revision"));
                require(!locators.isEmpty() && locators.size()<=500,MemoryErrorCode.MEMORY_UNAVAILABLE); sources.requireCurrentMetadata(context,locators);
                var body=store.one("SELECT response_json FROM memory_matter_receipt WHERE context_id=? AND source_event_id=?",id,source.sourceEventId());
                require(body.get("response_json")!=null,MemoryErrorCode.DELETED);
                var response=store.decode((String)body.get("response_json"),MemoryMatterWriteResponse.class);
                require(response.matter().contextKey().equals(context) && response.matter().matterId().equals(matterId)
                        && MemoryCanonicalHash.matterHash(response.matter()).equals(response.matter().version().contentHash())
                        && new HashSet<>(locators).containsAll(response.matter().dependencies()),MemoryErrorCode.MEMORY_UNAVAILABLE);
                sources.requireCurrentMetadata(context,response.matter().dependencies());
                MemoryV2RequestSecurity.requireUnexpired(proof); return response;
            }
            require(request.expectedConversationRevision()==number(conversation,"revision"),MemoryErrorCode.VERSION_CONFLICT);
            var intent=request.intent();
            var current=store.one("SELECT content_revision,state FROM memory_matter WHERE context_id=? AND matter_id=? FOR UPDATE",id,matterId);
            List<MemorySourceDependency> dependencies=new ArrayList<>(); MemoryMatter previous=null;
            if(current!=null) {
                require(Set.of("ACTIVE","CLOSED","REOPENED").contains(current.get("state")),MemoryErrorCode.WITHDRAWN);
                // Locators are authorized before opening a previous derived body.
                var locators=store.jdbc.query("SELECT dependency_json FROM memory_source_dependency WHERE target_context_id=? AND target_kind='MATTER' AND target_id=? AND target_revision=? LIMIT 501",
                        (rs,n) -> store.decode(rs.getString(1),MemorySourceDependency.class),id,matterId,number(current,"content_revision"));
                require(!locators.isEmpty() && locators.size()<=500,MemoryErrorCode.MEMORY_UNAVAILABLE);
                sources.requireCurrentMetadata(context,locators);
                var body=store.one("SELECT payload_json FROM memory_matter WHERE context_id=? AND matter_id=? AND content_revision=?",id,matterId,number(current,"content_revision"));
                previous=store.decode((String)body.get("payload_json"),MemoryMatter.class);
                require(previous.contextKey().equals(context) && previous.matterId().equals(matterId) && previous.version().revision()==number(current,"content_revision")
                        && MemoryCanonicalHash.matterHash(previous).equals(previous.version().contentHash()) && new HashSet<>(locators).equals(new HashSet<>(previous.dependencies())),MemoryErrorCode.MEMORY_UNAVAILABLE);
                dependencies.addAll(previous.dependencies());
            }
            if(intent.action()==MemoryMatterIntent.Action.START) require(current==null,MemoryErrorCode.VERSION_CONFLICT);
            else require(previous!=null && previous.version().revision()==intent.expectedRevision(),MemoryErrorCode.VERSION_CONFLICT);
            var action=intent.action();
            if(action==MemoryMatterIntent.Action.CONTINUE) require(previous.state()!=MemoryMatter.State.CLOSED,MemoryErrorCode.VERSION_CONFLICT);
            if(action==MemoryMatterIntent.Action.CLOSE) require(previous.state()!=MemoryMatter.State.CLOSED,MemoryErrorCode.VERSION_CONFLICT);
            if(action==MemoryMatterIntent.Action.REOPEN) require(previous.state()==MemoryMatter.State.CLOSED,MemoryErrorCode.VERSION_CONFLICT);
            if(!dependencies.contains(request.evidence())) dependencies.add(request.evidence());
            require(dependencies.size()<=500,MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
            MemoryMatter matter=previous;
            boolean applied=action!=MemoryMatterIntent.Action.CONTINUE;
            if(applied) {
                String goal=previous==null?intent.goal():previous.goal();
                var state=action==MemoryMatterIntent.Action.CLOSE?MemoryMatter.State.CLOSED:action==MemoryMatterIntent.Action.REOPEN?MemoryMatter.State.REOPENED:MemoryMatter.State.ACTIVE;
                var closure=action==MemoryMatterIntent.Action.CLOSE?new MemoryMatter.ClosureEvidence(MemoryMatter.ClosureKind.USER_CONFIRMATION,List.of(request.evidence())):previous==null?null:previous.closure();
                long revision=previous==null?1:previous.version().revision()+1;
                var draft=new MemoryMatter(matterId,context,new MemoryRevision(revision,"0".repeat(64),"matter.v2.1"),goal,state,closure,dependencies);
                matter=new MemoryMatter(matterId,context,new MemoryRevision(revision,MemoryCanonicalHash.matterHash(draft),draft.version().schemaVersion()),goal,state,closure,dependencies);
                String json=store.encode(matter);
                store.jdbc.update("INSERT INTO memory_matter_revision(context_id,matter_id,content_revision,content_hash,payload_json) VALUES(?,?,?,?,?)",id,matterId,revision,matter.version().contentHash(),json);
                if(current==null) store.jdbc.update("INSERT INTO memory_matter(context_id,matter_id,content_revision,state,payload_json) VALUES(?,?,?,?,?)",id,matterId,revision,state.name(),json);
                else store.jdbc.update("UPDATE memory_matter SET content_revision=?,state=?,payload_json=? WHERE context_id=? AND matter_id=?",revision,state.name(),json,id,matterId);
                store.dependencies(id,"MATTER",matterId,revision,dependencies);
                store.jdbc.update("UPDATE memory_conversation SET revision=revision+1 WHERE context_id=?",id);
            }
            var result=new MemoryMatterWriteResponse(matter,store.version(store.conversation(context)),applied);
            store.jdbc.update("INSERT INTO memory_matter_receipt(context_id,source_event_id,intent_hash,response_json) VALUES(?,?,?,?)",id,source.sourceEventId(),hash,store.encode(result));
            MemoryV2RequestSecurity.requireUnexpired(proof); return result;
        }); } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException exception) { throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE); }
    }
    private static final java.util.regex.Pattern CREDENTIAL=java.util.regex.Pattern.compile("(?i)(?:\\bBearer\\s+\\S+|\\b(?:password|passwd|access[_-]?token|refresh[_-]?token|client[_-]?secret|api[_-]?key|authorization)\\b\\s*[:=]\\s*\\S+|\\beyJ[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{8,})");
}
