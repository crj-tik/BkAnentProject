package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.memory.config.MemoryPreferenceProperties;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static com.bkanent.memory.service.MemoryArchiveServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class PreferenceHistoryServiceTest {
    static class Preferences extends Fixture {
        PreferenceHistoryService preferences;
        Preferences() throws Exception { super(); restartPreferences(); }
        void restartPreferences() { preferences = new PreferenceHistoryService(store, security, sources, new MemoryPreferenceProperties(), transactions); }
        MemoryPreferenceAssessment candidate(MemoryMessage source, String value, PreferenceTransition.Change change, List<String> corrects) {
            return new MemoryPreferenceAssessment("budget", PreferenceTransition.Assessment.PREFERENCE, change, PreferenceTransition.Polarity.AFFIRMATIVE,
                    MemoryJsonValue.string(value), null, null, null, null, corrects, .8, "extract-v1", new MemorySourceDependency(source.source(), null));
        }
        MemoryPreferenceWriteResponse submit(long revision, MemoryPreferenceAssessment candidate) {
            var request = request(MemoryV2Contract.OPERATION_PREFERENCE_WRITE, e -> new MemoryPreferenceWriteRequest(e, candidate.source().source().contextKey(), revision, candidate));
            return preferences.submit(request, sign(request));
        }
        MemoryPreferenceDetailResponse details(String id, PreferenceHistoryCursor cursor, int limit, Instant asOf, String matter) {
            var request = request(MemoryV2Contract.OPERATION_PREFERENCE_DETAIL, e -> new MemoryPreferenceDetailRequest(e, CONTEXT, id, cursor, limit, asOf, matter));
            return preferences.read(request, sign(request));
        }
        MemoryPreferenceDetailResponse details(String id) { return details(id, null, 100, Instant.now(), null); }
        void appendOther(MemoryMessage message) {
            var request = request(MemoryV2Contract.OPERATION_RECEIVE_PERMIT, e -> new MemoryReceivePermitRequest(e, message.contextKey(),
                    List.of(new MemoryReceivePermitRequest.EventSubmission(message.source().sourceEventId(), message.source().version().contentHash(), "conversation-archive"))));
            var permit = service.requestReceivePermit(request, sign(request));
            var delivery = request(MemoryV2Contract.OPERATION_DELIVER_MESSAGES, e -> new MemoryDeliveryRequest(e, message.contextKey(), message.association(), permit.permitId(), List.of(message)));
            service.deliverMessages(delivery, sign(delivery));
        }
    }

    @Test void preferenceBudgetEstimateAccountsForMultibyteTextConservatively() {
        var payload = "预算总价".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(4, new PreferenceSelectionPolicy().estimatedTokens(payload));
    }

    @Test void matterAssemblySelectsOnlyRelevantPrivateValuesAndPinsThemUntilBoundaryCheck() throws Exception {
        var fixture = new MemoryMatterServiceTest.MatterFixture();
        var archive = fixture.f;
        var properties = new MemoryPreferenceProperties();
        var preferences = new PreferenceHistoryService(archive.store, archive.security, archive.sources, properties, archive.transactions);

        var budgetSource = fixture.event(null);
        var budget = new MemoryPreferenceAssessment("budget", PreferenceTransition.Assessment.PREFERENCE,
                PreferenceTransition.Change.OBSERVATION, PreferenceTransition.Polarity.AFFIRMATIVE,
                MemoryJsonValue.string("100万"), null, null, null, null, List.of(), .8, "fixture-v1",
                new MemorySourceDependency(budgetSource.source(), null));
        var budgetWrite = archive.request(MemoryV2Contract.OPERATION_PREFERENCE_WRITE,
                e -> new MemoryPreferenceWriteRequest(e, CONTEXT, 0, budget));
        var budgetResult = preferences.submit(budgetWrite, archive.sign(budgetWrite));
        var expectedKey = new PreferenceBlockKey(CONTEXT.ownerId(), CONTEXT.ownerId(),
                MemoryScope.privateScope(CONTEXT.domainId(), CONTEXT.agentId()), "budget");
        assertEquals("p-" + MemoryCanonicalHash.contentHash(expectedKey), budgetResult.blockId(),
                "the assembly key must retain the persisted private block identity");

        var floorSource = fixture.event(null);
        var floor = new MemoryPreferenceAssessment("floor", PreferenceTransition.Assessment.PREFERENCE,
                PreferenceTransition.Change.OBSERVATION, PreferenceTransition.Polarity.AFFIRMATIVE,
                MemoryJsonValue.string("低楼层"), null, null, null, null, List.of(), .9, "fixture-v1",
                new MemorySourceDependency(floorSource.source(), null));
        var floorWrite = archive.request(MemoryV2Contract.OPERATION_PREFERENCE_WRITE,
                e -> new MemoryPreferenceWriteRequest(e, CONTEXT, 0, floor));
        preferences.submit(floorWrite, archive.sign(floorWrite));

        var start = fixture.event(new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房预算和总价上限"));
        var matter = fixture.write(start).matter();
        int transitionsBefore = archive.count("memory_preference_transition");
        var assemblyRequest = archive.request(MemoryV2Contract.OPERATION_ASSEMBLE_PREFERENCES,
                e -> new MemoryPreferenceAssemblyRequest(e, CONTEXT, matter.matterId(), 800));
        var assembled = preferences.assemble(assemblyRequest, archive.sign(assemblyRequest));

        assertEquals(matter.version().revision(), assembled.matterRevision());
        assertEquals(MemoryCanonicalHash.contentHash(matter.goal()), assembled.objectiveHash());
        assertEquals("preference-selection-v1", assembled.scoringVersion());
        assertEquals(List.of("budget"), assembled.matches().stream().map(MemoryPreferenceMatch::dimensionId).toList());
        var match = assembled.matches().get(0);
        assertEquals("100万", match.effectiveState().value().scalar());
        assertEquals(.8, match.score().confidence());
        assertEquals(1.0, match.score().activation());
        assertTrue(match.score().relevance() > 0);
        assertTrue(match.score().timeWeight() > 0 && match.score().timeWeight() <= 1);
        assertEquals(assembled.asOf(), match.score().asOf());
        assertEquals(transitionsBefore, archive.count("memory_preference_transition"),
                "selection and scoring must not create evidence");

        var limitedRequest = archive.request(MemoryV2Contract.OPERATION_ASSEMBLE_PREFERENCES,
                e -> new MemoryPreferenceAssemblyRequest(e, CONTEXT, matter.matterId(), 1));
        var limited = preferences.assemble(limitedRequest, archive.sign(limitedRequest));
        assertTrue(limited.budgetLimited());
        assertTrue(limited.matches().isEmpty());
        assertEquals(transitionsBefore, archive.count("memory_preference_transition"));

        archive.service = new MemoryArchiveService(archive.store, archive.security, archive.sources, preferences, archive.transactions);
        var boundary = archive.request(MemoryV2Contract.OPERATION_BOUNDARY,
                e -> new MemoryBoundaryRequest(e, CONTEXT, archive.read().version(), List.of(), assembled.selections()));
        assertTrue(archive.service.checkBoundary(boundary, archive.sign(boundary)).unchanged());

        var exceptionSource = fixture.event(new MemoryMatterIntent(MemoryMatterIntent.Action.CONTINUE,
                matter.matterId(), matter.version().revision(), null));
        Instant exceptionFrom = Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant exceptionUntil = Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var exception = new MemoryPreferenceAssessment("budget", PreferenceTransition.Assessment.PREFERENCE,
                PreferenceTransition.Change.TEMPORARY_EXCEPTION, PreferenceTransition.Polarity.AFFIRMATIVE,
                MemoryJsonValue.string("120万"), "仅当前事项", matter.matterId(), exceptionFrom, exceptionUntil, List.of(), .85, "fixture-v1",
                new MemorySourceDependency(exceptionSource.source(), null));
        var exceptionWrite = archive.request(MemoryV2Contract.OPERATION_PREFERENCE_WRITE,
                e -> new MemoryPreferenceWriteRequest(e, CONTEXT, 1, exception));
        preferences.submit(exceptionWrite, archive.sign(exceptionWrite));
        var exceptionRequest = archive.request(MemoryV2Contract.OPERATION_ASSEMBLE_PREFERENCES,
                e -> new MemoryPreferenceAssemblyRequest(e, CONTEXT, matter.matterId(), 800));
        var exceptionAssembly = preferences.assemble(exceptionRequest, archive.sign(exceptionRequest));
        assertEquals("120万", exceptionAssembly.matches().get(0).effectiveState().value().scalar());
        assertFalse(exceptionAssembly.matches().get(0).effectiveState().exceptionTransitionIds().isEmpty());
        var exceptionBoundary = archive.request(MemoryV2Contract.OPERATION_BOUNDARY,
                e -> new MemoryBoundaryRequest(e, CONTEXT, archive.read().version(), List.of(), exceptionAssembly.selections()));
        assertTrue(archive.service.checkBoundary(exceptionBoundary, archive.sign(exceptionBoundary)).unchanged());

        var staleBoundary = archive.request(MemoryV2Contract.OPERATION_BOUNDARY,
                e -> new MemoryBoundaryRequest(e, CONTEXT, archive.read().version(), List.of(), assembled.selections()));
        code(MemoryErrorCode.VERSION_CONFLICT, () -> archive.service.checkBoundary(staleBoundary, archive.sign(staleBoundary)));

        var withdrawal = archive.request(MemoryV2Contract.OPERATION_WITHDRAW,
                e -> new MemoryWithdrawalRequest(e, CONTEXT, exceptionSource.source().sourceEventId(), false));
        archive.service.withdrawSource(withdrawal, archive.sign(withdrawal));
        var revokedBoundary = archive.request(MemoryV2Contract.OPERATION_BOUNDARY,
                e -> new MemoryBoundaryRequest(e, CONTEXT, archive.read().version(), List.of(), exceptionAssembly.selections()));
        code(MemoryErrorCode.VERSION_CONFLICT, () -> archive.service.checkBoundary(revokedBoundary, archive.sign(revokedBoundary)));
    }

    @Test void stableBlockPreservesNegativePolarityExactSourceAndIdempotentReceipt() throws Exception {
        var f = new Preferences(); var source = message(1, "do not exceed budget"); f.append(List.of(source));
        var original = f.candidate(source, "100", PreferenceTransition.Change.OBSERVATION, List.of());
        var candidate = new MemoryPreferenceAssessment(original.dimensionId(), original.assessment(), original.change(), PreferenceTransition.Polarity.NEGATIVE,
                original.value(), null, null, null, null, List.of(), .8, original.extractionVersion(), original.source());
        var first = f.submit(0, candidate); assertEquals(first, f.submit(0, candidate));
        var acceptedTime=f.jdbc.queryForObject("SELECT accepted_at FROM memory_message_observation",Object.class);
        f.append(List.of(source)); assertEquals(acceptedTime,f.jdbc.queryForObject("SELECT accepted_at FROM memory_message_observation",Object.class));
        f.restart(); f.restartPreferences(); var details = f.details(first.blockId());
        assertEquals(PreferenceTransition.Polarity.NEGATIVE, details.effectiveState().polarity());
        assertEquals(1, details.history().transitions().size()); assertEquals(1, f.count("memory_preference_transition"));
        assertEquals(MemoryScope.privateScope("marketing", "copy-agent"), details.block().key().scope());
        assertEquals(List.of(new MemorySourceDependency(source.source(), null)), details.effectiveState().evidence());
        code(MemoryErrorCode.VERSION_CONFLICT, () -> f.submit(1, f.candidate(source, "200", PreferenceTransition.Change.OBSERVATION, List.of())));
    }

    @Test void lateOldEvidenceCannotOverrideExplicitNewCorrection() throws Exception {
        var f = new Preferences(); var messages = List.of(message(1,"old"), message(2,"intermediate"), message(3,"correction")); f.append(messages);
        var first = f.submit(0, f.candidate(messages.get(0), "100", PreferenceTransition.Change.OBSERVATION, List.of()));
        var corrected = f.submit(1, f.candidate(messages.get(2), "300", PreferenceTransition.Change.LONG_TERM_CORRECTION, List.of(first.transitionId())));
        var oldPage = f.details(first.blockId(),null,1,Instant.now(),null);
        f.submit(2, f.candidate(messages.get(1), "200", PreferenceTransition.Change.OBSERVATION, List.of()));
        var response = f.details(corrected.blockId());
        assertEquals("300", response.effectiveState().value().scalar()); assertEquals(List.of(corrected.transitionId()), response.effectiveState().transitionIds());
        assertEquals(List.of(1L,2L,3L), response.history().transitions().stream().map(t -> t.evidence().get(0).source().sourceSequence()).toList());
        var oldRest=f.details(first.blockId(),oldPage.history().nextCursor(),10,oldPage.asOf(),null);
        assertEquals(2,oldRest.block().version().revision()); assertEquals(3L,oldRest.history().transitions().get(0).evidence().get(0).source().sourceSequence());
        assertEquals(3, response.history().transitions().size());
    }

    @Test void crossConversationSequencesDoNotEstablishPrecedenceButExplicitCorrectionDoes() throws Exception {
        var f = new Preferences(); var first = message(1, "first"); f.append(List.of(first));
        var a = f.submit(0, f.candidate(first, "100", PreferenceTransition.Change.OBSERVATION, List.of()));
        var other = otherMessage(new MemoryContextKey("42","marketing","copy-agent","other-conversation"), "other-event", "other"); f.appendOther(other);
        var b = f.submit(1, f.candidate(other, "200", PreferenceTransition.Change.OBSERVATION, List.of()));
        assertEquals(a.blockId(), b.blockId()); assertEquals(PreferenceCurrentState.Status.UNRESOLVED, f.details(a.blockId()).effectiveState().status());
        var third = otherMessage(new MemoryContextKey("42","marketing","copy-agent","third-conversation"), "third-event", "explicit correction"); f.appendOther(third);
        f.submit(2, f.candidate(third, "300", PreferenceTransition.Change.LONG_TERM_CORRECTION, List.of(a.transitionId(), b.transitionId())));
        assertEquals("300", f.details(a.blockId()).effectiveState().value().scalar());
    }

    @Test void scopedExceptionExpiresAtOneAsOfWithoutChangingLongTermDefaultOrObservation() throws Exception {
        var f = new Preferences(); var a = message(1,"default"); var b = message(2,"this matter only"); f.append(List.of(a,b));
        var first = f.submit(0, f.candidate(a, "100", PreferenceTransition.Change.OBSERVATION, List.of()));
        Instant start = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), end = start.plusSeconds(30);
        var candidate = new MemoryPreferenceAssessment("budget", PreferenceTransition.Assessment.PREFERENCE, PreferenceTransition.Change.TEMPORARY_EXCEPTION,
                PreferenceTransition.Polarity.AFFIRMATIVE, MemoryJsonValue.string("200"), "only this matter", "matter-1", start, end, List.of(), .7,"extract-v1", new MemorySourceDependency(b.source(),null));
        f.submit(1, candidate);
        assertEquals("200", f.details(first.blockId(),null,10,start.plusSeconds(1),"matter-1").effectiveState().value().scalar());
        var expired = f.details(first.blockId(),null,10,end,"matter-1");
        assertEquals("100", expired.effectiveState().value().scalar()); assertEquals(2, expired.history().transitions().size());
        assertEquals("100", f.details(first.blockId(),null,10,start.plusSeconds(1),"other-matter").effectiveState().value().scalar());
        var saved = f.jdbc.queryForList("SELECT snapshot_json FROM memory_preference_revision ORDER BY revision",String.class);
        f.details(first.blockId()); f.details(first.blockId());
        assertEquals(saved, f.jdbc.queryForList("SELECT snapshot_json FROM memory_preference_revision ORDER BY revision",String.class));
    }

    @Test void paginationKeepsOldRevisionWhenNewEvidenceArrivesAndNeverMixesPages() throws Exception {
        var f = new Preferences(); var messages = List.of(message(1,"one"),message(2,"two"),message(3,"three")); f.append(messages);
        var first = f.submit(0,f.candidate(messages.get(0),"1",PreferenceTransition.Change.OBSERVATION,List.of()));
        f.submit(1,f.candidate(messages.get(1),"2",PreferenceTransition.Change.OBSERVATION,List.of()));
        var page = f.details(first.blockId(),null,1,Instant.now(),null); assertTrue(page.history().hasMore());
        f.submit(2,f.candidate(messages.get(2),"3",PreferenceTransition.Change.OBSERVATION,List.of()));
        var rest = f.details(first.blockId(),page.history().nextCursor(),1,page.asOf(),null);
        assertEquals(2,rest.block().version().revision()); assertEquals("2",rest.effectiveState().value().scalar());
        assertEquals(1,rest.history().transitions().size()); assertFalse(rest.history().hasMore());
        assertEquals(PreferenceHistoryPage.Completeness.COMPLETE,rest.history().completeness());
        assertEquals(3,f.details(first.blockId()).block().version().revision());
    }

    @Test void withdrawalAndDeletionInvalidateOldPagesAndPreventLateReappearance() throws Exception {
        for (boolean delete : List.of(false,true)) {
            var f = new Preferences(); var source = message(1,"fact"); f.append(List.of(source));
            var candidate = f.candidate(source,"100",PreferenceTransition.Change.OBSERVATION,List.of()); var accepted = f.submit(0,candidate);
            var old = f.details(accepted.blockId());
            var request = f.request(MemoryV2Contract.OPERATION_WITHDRAW,e -> new MemoryWithdrawalRequest(e,CONTEXT,source.source().sourceEventId(),delete));
            f.service.withdrawSource(request,f.sign(request));
            code(delete ? MemoryErrorCode.DELETED : MemoryErrorCode.WITHDRAWN, () -> f.details(accepted.blockId(),old.history().cursor(),10,old.asOf(),null));
            assertThrows(MemoryException.class, () -> f.submit(0,candidate));
            if (delete) assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_preference_transition WHERE payload_json IS NOT NULL",Integer.class));
        }
    }

    @Test void anotherAgentCannotReadBlockAndForgedDimensionsOrAssistantEvidenceAreRejected() throws Exception {
        var f = new Preferences(); var source = message(1,"fact"); f.append(List.of(source));
        var first = f.submit(0,f.candidate(source,"100",PreferenceTransition.Change.OBSERVATION,List.of()));
        f.caller.setAllowedAgentsByDomain(Map.of("marketing",Set.of("copy-agent","other-agent")));
        var guarded=org.mockito.Mockito.spy(f.store);
        f.preferences=new PreferenceHistoryService(guarded,f.security,f.sources,new MemoryPreferenceProperties(),f.transactions);
        Instant asOf=Instant.now();
        var request = f.request(MemoryV2Contract.OPERATION_PREFERENCE_DETAIL,e -> new MemoryPreferenceDetailRequest(e,
                new MemoryContextKey("42","marketing","other-agent",CONTEXT.conversationId()),first.blockId(),null,10,asOf,null));
        code(MemoryErrorCode.FORBIDDEN, () -> f.preferences.read(request,f.sign(request)));
        org.mockito.Mockito.verify(guarded,org.mockito.Mockito.never()).decode(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(PreferenceBlock.class));
        org.mockito.Mockito.verify(guarded,org.mockito.Mockito.never()).decode(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(PreferenceTransition.class));
        var bad = new MemoryPreferenceAssessment("new-key-from-each-sentence",PreferenceTransition.Assessment.PREFERENCE,PreferenceTransition.Change.OBSERVATION,
                PreferenceTransition.Polarity.AFFIRMATIVE,MemoryJsonValue.string("x"),null,null,null,null,List.of(),.8,"extract-v1",new MemorySourceDependency(source.source(),null));
        code(MemoryErrorCode.INVALID_EXTRACTION, () -> f.submit(1,bad));
        var assistant = build(2,MemoryMessage.Role.ASSISTANT,"self report",List.of(),List.of()); f.append(List.of(assistant));
        code(MemoryErrorCode.INVALID_EXTRACTION, () -> f.submit(1,f.candidate(assistant,"200",PreferenceTransition.Change.OBSERVATION,List.of())));
    }

    @Test void questionAndTaskConstraintNeverBecomeLongTermObservations() throws Exception {
        var f = new Preferences(); var source = message(1,"is this feasible?"); f.append(List.of(source));
        for (var assessment : List.of(PreferenceTransition.Assessment.QUESTION,PreferenceTransition.Assessment.TASK_CONSTRAINT,PreferenceTransition.Assessment.OTHER)) {
            var candidate = new MemoryPreferenceAssessment("budget",assessment,PreferenceTransition.Change.OBSERVATION,PreferenceTransition.Polarity.AFFIRMATIVE,
                    MemoryJsonValue.string("100"),null,null,null,null,List.of(),.8,"extract-v1",new MemorySourceDependency(source.source(),null));
            assertFalse(f.submit(0,candidate).applied());
        }
        assertEquals(0,f.count("memory_preference_block")); assertEquals(0,f.count("memory_preference_transition"));
    }

    @Test void userRevokedDuringReadWithholdsHistoryAndTamperedTransitionFailsClosed() throws Exception {
        var f = new Preferences(); var source = message(1,"fact"); f.append(List.of(source)); var first = f.submit(0,f.candidate(source,"100",PreferenceTransition.Change.OBSERVATION,List.of()));
        int before=f.auth.calls; f.auth.onCheck=() -> { if(f.auth.calls>=before+2) f.auth.allowed=false; };
        code(MemoryErrorCode.FORBIDDEN, () -> f.details(first.blockId()));
        f.auth.allowed=true; f.auth.onCheck=() -> {};
        f.jdbc.update("UPDATE memory_preference_transition SET transition_hash=?", "0".repeat(64));
        code(MemoryErrorCode.MEMORY_UNAVAILABLE, () -> f.details(first.blockId()));
    }

    @Test void missingTrustedTimeAndCredentialShapedCandidateAreRejectedWithoutHistory() throws Exception {
        var f = new Preferences(); var source=message(1,"fact"); f.append(List.of(source));
        code(MemoryErrorCode.INVALID_EXTRACTION, () -> f.submit(0,f.candidate(source,"password=not-allowed",PreferenceTransition.Change.OBSERVATION,List.of())));
        code(MemoryErrorCode.INVALID_EXTRACTION, () -> f.submit(0,f.candidate(source," ",PreferenceTransition.Change.OBSERVATION,List.of())));
        var candidate=f.candidate(source,"100",PreferenceTransition.Change.OBSERVATION,List.of());
        var request=f.request(MemoryV2Contract.OPERATION_PREFERENCE_WRITE,e -> new MemoryPreferenceWriteRequest(e,CONTEXT,0,candidate));
        var proof=f.sign(request);
        var serviceProof=new MemoryRequestProof(proof.keyId(),proof.issuedAtEpochSecond(),proof.expiresAtEpochSecond(),proof.nonce(),proof.signature(),MemoryRequestProof.Actor.SERVICE);
        code(MemoryErrorCode.AUTHENTICATION_FAILED, () -> f.preferences.submit(request,serviceProof));
        f.jdbc.update("DELETE FROM memory_message_observation");
        code(MemoryErrorCode.MEMORY_UNAVAILABLE, () -> f.submit(0,f.candidate(source,"100",PreferenceTransition.Change.OBSERVATION,List.of())));
        assertEquals(0,f.count("memory_preference_transition"));
    }

    @Test void twoWritersWithSameExpectedRevisionCannotBothAppend() throws Exception {
        var f = new Preferences(); var a=message(1,"one"); var b=message(2,"two"); f.append(List.of(a,b));
        var first=CompletableFuture.supplyAsync(() -> { try { return f.submit(0,f.candidate(a,"100",PreferenceTransition.Change.OBSERVATION,List.of())); } catch(MemoryException ex){return ex.code();} });
        var second=CompletableFuture.supplyAsync(() -> { try { return f.submit(0,f.candidate(b,"200",PreferenceTransition.Change.OBSERVATION,List.of())); } catch(MemoryException ex){return ex.code();} });
        var results=List.of(first.join(),second.join());
        assertEquals(1,results.stream().filter(MemoryPreferenceWriteResponse.class::isInstance).count());
        assertTrue(results.contains(MemoryErrorCode.VERSION_CONFLICT) || results.contains(MemoryErrorCode.MEMORY_UNAVAILABLE));
        assertEquals(1,f.count("memory_preference_transition"));
    }

    @Test void historyBeyondOnePageHasStableIdentityAndAll601TransitionsRemainReadable() throws Exception {
        var f=new Preferences();
        List<MemoryMessage> messages=new ArrayList<>();
        for(int i=1;i<=601;i++) messages.add(message(i,"source-"+i));
        for(int start=0;start<messages.size();start+=20) f.append(messages.subList(start,Math.min(start+20,messages.size())));
        var accepted=f.submit(0,f.candidate(messages.get(0),"1",PreferenceTransition.Change.OBSERVATION,List.of()));
        // Seed a long valid immutable revision to isolate pagination from 600 repeated model/merge operations.
        for(int i=2;i<=601;i++) {
            var source=new MemorySourceDependency(messages.get(i-1).source(),null);
            Instant occurred=MemoryArchiveStore.utcInstant(f.jdbc.queryForObject("SELECT accepted_at FROM memory_message_observation WHERE context_id=? AND source_event_id=?",Object.class,MemoryArchiveStore.key(CONTEXT),source.source().sourceEventId()));
            var transition=new PreferenceTransition("t-seeded-"+i,i,i,PreferenceTransition.Assessment.PREFERENCE,PreferenceTransition.Change.OBSERVATION,
                    PreferenceTransition.Polarity.AFFIRMATIVE,MemoryJsonValue.string(Integer.toString(i)),null,null,occurred,occurred,occurred,null,List.of(),.8,"fixture-v1",List.of(source));
            f.jdbc.update("INSERT INTO memory_preference_transition(block_id,transition_id,history_ordinal,introduced_revision,source_context_id,source_event_id,candidate_hash,transition_hash,source_json,payload_json) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    accepted.blockId(),transition.transitionId(),i,i,MemoryArchiveStore.key(CONTEXT),source.source().sourceEventId(),ZERO,MemoryCanonicalHash.contentHash(transition),f.mapper.writeValueAsString(source),f.mapper.writeValueAsString(transition));
        }
        var first=f.details(accepted.blockId()).block();
        var draft=new PreferenceBlock(first.blockId(),first.key(),new MemoryRevision(601,ZERO,"preference.v2.1"),MemoryState.ACTIVE,first.currentState(),new PreferenceHistoryCursor(first.blockId(),601,1,0));
        var snapshot=new PreferenceBlock(draft.blockId(),draft.key(),new MemoryRevision(601,PreferenceHistoryService.blockHash(draft),draft.version().schemaVersion()),draft.state(),draft.currentState(),draft.historyStart());
        f.jdbc.update("INSERT INTO memory_preference_revision(block_id,revision,content_hash,snapshot_json) VALUES(?,?,?,?)",snapshot.blockId(),601,snapshot.version().contentHash(),f.mapper.writeValueAsString(snapshot));
        f.jdbc.update("UPDATE memory_preference_block SET revision=601,last_ordinal=601 WHERE block_id=?",snapshot.blockId());
        var page=f.details(snapshot.blockId(),null,500,Instant.now(),null); assertTrue(page.history().hasMore()); assertEquals(500,page.history().transitions().size());
        var last=f.details(snapshot.blockId(),page.history().nextCursor(),500,page.asOf(),null); assertEquals(101,last.history().transitions().size());
        assertEquals(601L,last.history().coveredThroughOrdinal()); assertFalse(last.history().hasMore());
        assertEquals(snapshot.blockId(),last.block().blockId()); assertEquals(601,last.block().version().revision());
    }

    static MemoryMessage otherMessage(MemoryContextKey context,String event,String text) {
        var source=new MemorySourceRef(MemorySourceRef.Kind.MESSAGE,context,"m-"+event,event,1L,new MemoryRevision(1,ZERO,"message.v2"));
        var draft=new MemoryMessage(source.sourceId(),context,1,ASSOCIATION,MemoryMessage.Role.USER,text,List.of(),List.of(),source);
        return new MemoryMessage(source.sourceId(),context,1,ASSOCIATION,MemoryMessage.Role.USER,text,List.of(),List.of(),
                new MemorySourceRef(source.kind(),context,source.sourceId(),event,1L,new MemoryRevision(1,MemoryCanonicalHash.messageHash(draft),"message.v2")));
    }
}
