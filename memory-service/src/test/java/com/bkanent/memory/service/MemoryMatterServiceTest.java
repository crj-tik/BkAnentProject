package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static com.bkanent.memory.service.MemoryArchiveServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryMatterServiceTest {
    @Test void explicitUserStartContinueCloseReopenAndReceiptsSurviveRestart() throws Exception {
        var f = new MatterFixture();
        var start = f.event(new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房预算"));
        var first = f.write(start); String id = first.matter().matterId();
        assertEquals(MemoryMatter.State.ACTIVE, first.matter().state());
        var continueEvent = f.event(new MemoryMatterIntent(MemoryMatterIntent.Action.CONTINUE, id, 1, null));
        long revision = f.f.read().version().revision();
        var continued = f.write(continueEvent);
        assertFalse(continued.applied()); assertEquals(revision, continued.version().revision());
        var close = f.event(new MemoryMatterIntent(MemoryMatterIntent.Action.CLOSE, id, 1, null));
        var closed = f.write(close);
        assertEquals(MemoryMatter.State.CLOSED, closed.matter().state());
        assertEquals(MemoryMatter.ClosureKind.USER_CONFIRMATION, closed.matter().closure().kind());
        assertEquals(List.of(new MemorySourceDependency(close.source(), null)), closed.matter().closure().evidence());
        f.f.restart(); f.restart();
        assertEquals(closed, f.write(close));
        var reopened = f.write(f.event(new MemoryMatterIntent(MemoryMatterIntent.Action.REOPEN, id, 2, null)));
        assertEquals(MemoryMatter.State.REOPENED, reopened.matter().state());
        assertEquals(3, reopened.matter().version().revision()); assertEquals(3, f.f.count("memory_matter_revision"));
        assertEquals(List.of(reopened.matter()), f.f.read().matters());
        assertEquals(first, f.write(start), "a lost old reply is replayed, never reapplied");
        assertEquals(List.of(reopened.matter()), f.f.read().matters());
    }
    @Test void untypedTextAndToolCompletionCannotCloseAMatter() throws Exception {
        var f = new MatterFixture(); var ordinary = f.event(null);
        var forged = new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "模型自称完成");
        var request = f.request(ordinary, forged, f.writer);
        code(MemoryErrorCode.FORBIDDEN, () -> f.service.transition(request, f.f.sign(request)));
        assertEquals(0, f.f.count("memory_matter"));
        var m = ordinary;
        assertThrows(MemoryException.class, () -> new MemoryMessage(m.messageId(), CONTEXT, m.sequence(), m.association(), MemoryMessage.Role.ASSISTANT, "done", List.of(), List.of(), m.source(), List.of(), forged));
    }
    @Test void staleMatterRevisionWriterAndOtherCallerAreRejected() throws Exception {
        var f = new MatterFixture(); var start = f.event(new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房"));
        var first = f.write(start);
        var close = f.event(new MemoryMatterIntent(MemoryMatterIntent.Action.CLOSE, first.matter().matterId(), 2, null));
        code(MemoryErrorCode.VERSION_CONFLICT, () -> f.write(close));
        var badWriter = f.request(start, start.matterIntent(), new MemoryWriteAuthority("other-run", f.writer.fence()));
        code(MemoryErrorCode.VERSION_CONFLICT, () -> f.service.transition(badWriter, f.f.sign(badWriter)));
        long revision = f.f.read().version().revision();
        var other = f.f.request(MemoryV2Contract.OPERATION_MATTER_WRITE, "other-service", UUID.randomUUID().toString(), e -> new MemoryMatterWriteRequest(e, CONTEXT, start.matterIntent(), new MemorySourceDependency(start.source(), null), revision, f.writer));
        code(MemoryErrorCode.VERSION_CONFLICT, () -> f.service.transition(other, f.f.sign(other)));
        assertEquals(1, f.f.count("memory_matter_revision"));
    }
    @Test void concurrentSameSourceTransitionIsOneImmutableRevision() throws Exception {
        var f = new MatterFixture(); var start = f.event(new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房"));
        var request = f.request(start, start.matterIntent(), f.writer);
        var a = CompletableFuture.supplyAsync(() -> f.service.transition(request, f.f.sign(request)));
        var b = CompletableFuture.supplyAsync(() -> f.service.transition(request, f.f.sign(request)));
        assertEquals(a.join(), b.join()); assertEquals(1, f.f.count("memory_matter_revision")); assertEquals(1, f.f.count("memory_matter_receipt"));
    }
    @Test void revokedMetadataIsRejectedBeforeCorruptMatterBodyIsOpened() throws Exception {
        var f = new MatterFixture(); var start = f.event(new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房")); f.write(start);
        f.f.jdbc.update("UPDATE memory_matter SET payload_json='invalid-json'");
        f.f.jdbc.update("UPDATE memory_message SET state='WITHDRAWN'");
        code(MemoryErrorCode.WITHDRAWN, f.f::read);
        var query = f.f.request(MemoryV2Contract.OPERATION_QUERY_MATTERS, e -> new MemoryMatterQueryRequest(e, CONTEXT, null, 20));
        code(MemoryErrorCode.WITHDRAWN, () -> f.f.service.queryMatters(query, f.f.sign(query)));
        code(MemoryErrorCode.WITHDRAWN, () -> f.write(start));
    }
    @Test void deletingOriginRemovesMatterRevisionAndReceiptBodies() throws Exception {
        var f = new MatterFixture(); var start = f.event(new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房")); f.write(start);
        var delete = f.f.request(MemoryV2Contract.OPERATION_WITHDRAW, e -> new MemoryWithdrawalRequest(e, CONTEXT, start.source().sourceEventId(), true));
        f.f.service.withdrawSource(delete, f.f.sign(delete));
        assertTrue(f.f.read().matters().isEmpty());
        assertEquals(0, f.f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_matter_revision WHERE payload_json IS NOT NULL", Integer.class));
        assertEquals(0, f.f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_matter_receipt WHERE response_json IS NOT NULL", Integer.class));
        code(MemoryErrorCode.WITHDRAWN, () -> f.write(start));
    }
    @Test void changedMatterBodyHashAndCredentialGoalAreRejected() throws Exception {
        var f = new MatterFixture(); var start = f.event(new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房")); f.write(start);
        String body = f.f.jdbc.queryForObject("SELECT payload_json FROM memory_matter", String.class);
        f.f.jdbc.update("UPDATE memory_matter SET payload_json=?", body.replace("买房", "卖房"));
        code(MemoryErrorCode.MEMORY_UNAVAILABLE, f.f::read);
        var other = new MatterFixture(); var unsafe = other.event(new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "password=should-not-store"));
        code(MemoryErrorCode.INVALID_EXTRACTION, () -> other.write(unsafe));
    }
    @Test void revokedEntityMetadataIsRejectedBeforeCorruptBodyIsOpened() throws Exception {
        var f = new Fixture(); var message = MemoryArchiveServiceTest.message(1, "fact"); f.append(List.of(message));
        var dep = new MemorySourceDependency(message.source(), null);
        var base = summary(List.of(message), 1, List.of(), List.of(new MemoryEntityAnchor("entity", CONTEXT, null, new MemoryRevision(1, ZERO, "entity.v2.1"), List.of("fact"), "fact", List.of(dep))));
        var submit = f.request(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, e -> new MemorySummaryRequest(e, base));
        f.service.submitSummary(submit, f.sign(submit));
        f.jdbc.update("UPDATE memory_entity_anchor SET payload_json='invalid-json'"); f.jdbc.update("UPDATE memory_message SET state='WITHDRAWN'");
        code(MemoryErrorCode.WITHDRAWN, () -> f.entities(null, 20));
    }
    static MemoryMessage message(MemoryContextKey context, long sequence, MemoryMatterIntent intent) {
        String id = "message-" + sequence;
        var association = new MemoryAssociation(intent == null ? null : intent.matterId(), "run-1", null, null);
        var source = new MemorySourceRef(MemorySourceRef.Kind.MESSAGE, context, id, "event-" + sequence, sequence, new MemoryRevision(1, ZERO, "message.v2"));
        var draft = new MemoryMessage(id, context, sequence, association, MemoryMessage.Role.USER, "用户原话", List.of(), List.of(), source, List.of(), intent);
        return new MemoryMessage(id, context, sequence, association, draft.role(), draft.text(), List.of(), List.of(), new MemorySourceRef(source.kind(), context, id, source.sourceEventId(), sequence, new MemoryRevision(1, MemoryCanonicalHash.messageHash(draft), "message.v2")), List.of(), intent);
    }
    static class MatterFixture {
        final Fixture f = new Fixture();
        final MemoryWriteAuthority writer;
        MemoryMatterService service;
        MatterFixture() throws Exception {
            var acquire = f.request(MemoryV2Contract.OPERATION_WRITER, e -> new MemoryWriterRequest(e, CONTEXT, MemoryWriterRequest.Action.ACQUIRE, "run-1", 0));
            writer = f.service.manageWriter(acquire, f.sign(acquire)).authority(); restart();
        }
        void restart() { service = new MemoryMatterService(f.store, f.security, f.sources, f.transactions); }
        MemoryMessage event(MemoryMatterIntent intent) {
            var m = message(CONTEXT, f.read().version().lastSequence() + 1, intent);
            var p = f.request(MemoryV2Contract.OPERATION_RECEIVE_PERMIT, e -> new MemoryReceivePermitRequest(e, CONTEXT, List.of(new MemoryReceivePermitRequest.EventSubmission(m.source().sourceEventId(), m.source().version().contentHash(), "conversation-archive")), List.of(), writer));
            var permit = f.service.requestReceivePermit(p, f.sign(p));
            var d = f.request(MemoryV2Contract.OPERATION_DELIVER_MESSAGES, e -> new MemoryDeliveryRequest(e, CONTEXT, m.association(), permit.permitId(), List.of(m)));
            f.service.deliverMessages(d, f.signService(d)); return m;
        }
        MemoryMatterWriteRequest request(MemoryMessage m, MemoryMatterIntent intent, MemoryWriteAuthority authority) {
            long revision = f.jdbc.queryForObject("SELECT revision FROM memory_conversation", Long.class);
            return f.request(MemoryV2Contract.OPERATION_MATTER_WRITE, e -> new MemoryMatterWriteRequest(e, CONTEXT, intent, new MemorySourceDependency(m.source(), null), revision, authority));
        }
        MemoryMatterWriteResponse write(MemoryMessage m) { var request = request(m, m.matterIntent(), writer); return service.transition(request, f.sign(request)); }
    }
}
