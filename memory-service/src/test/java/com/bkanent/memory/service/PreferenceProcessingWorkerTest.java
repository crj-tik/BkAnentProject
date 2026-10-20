package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.memory.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static com.bkanent.memory.service.MemoryArchiveServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class PreferenceProcessingWorkerTest {
    static class Processing extends PreferenceHistoryServiceTest.Preferences {
        final MemoryPreferenceProcessingProperties processing=new MemoryPreferenceProcessingProperties();
        final AtomicInteger modelCalls=new AtomicInteger();
        Function<PreferenceEvaluator.Evidence,PreferenceEvaluator.Result> evaluation=this::normal;
        String version="fixture-eval-v1";
        final Clock clock=Clock.fixed(Instant.now().plusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MICROS),ZoneOffset.UTC);
        final PreferenceEvaluator evaluator=new PreferenceEvaluator() {
            public String version() { return version; }
            public Result evaluate(Evidence evidence) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); modelCalls.incrementAndGet();
                return evaluation.apply(evidence);
            }
        };
        Processing() throws Exception {
            super(); processing.setEnabled(true); processing.setMode(MemoryPreferenceProcessingProperties.Mode.PUBLISH);
            processing.setAllowedAgentsByDomain(Map.of("marketing",Set.of("copy-agent")));
        }
        PreferenceProcessingWorker worker() {
            return new PreferenceProcessingWorker(store,sources,preferences,new MemoryPreferenceProperties(),processing,properties,evaluator,transactions,clock);
        }
        PreferenceEvaluator.Result normal(PreferenceEvaluator.Evidence evidence) {
            var message=evidence.message();
            return new PreferenceEvaluator.Result(PreferenceTransition.Assessment.PREFERENCE,List.of(new MemoryPreferenceAssessment("budget",PreferenceTransition.Assessment.PREFERENCE,
                    message.sequence()>1?PreferenceTransition.Change.LONG_TERM_CORRECTION:PreferenceTransition.Change.OBSERVATION,PreferenceTransition.Polarity.AFFIRMATIVE,
                    MemoryJsonValue.string(message.text()),null,null,null,null,List.of(),.8,version,new MemorySourceDependency(message.source(),null))));
        }
        String state(int event) { return jdbc.queryForObject("SELECT state FROM memory_processing_job WHERE source_event_id=? AND job_kind='ASSESS_MESSAGE'",String.class,"event-"+event); }
        String block() { return jdbc.queryForObject("SELECT block_id FROM memory_preference_block",String.class); }
        void due() { jdbc.update("UPDATE memory_processing_job SET available_at=?",LocalDateTime.of(2020,1,1,0,0)); }
        PreferenceProcessingWorker.Claim claim(PreferenceProcessingWorker worker) { return new TransactionTemplate(transactions).execute(status -> worker.claim()); }
        void withdraw(boolean delete) {
            var request=request(MemoryV2Contract.OPERATION_WITHDRAW,e -> new MemoryWithdrawalRequest(e,CONTEXT,"event-1",delete));
            service.withdrawSource(request,sign(request));
        }
    }

    @Test void microbatchConsolidatesInSourceOrderAndDoesNotRepeatObservations() throws Exception {
        var f=new Processing(); f.append(List.of(message(1,"100万"),message(2,"改成200万")));
        assertEquals(0,f.modelCalls.get()); assertEquals(0,f.count("memory_preference_block"));
        f.worker().runOnce(); assertEquals("SUCCEEDED",f.state(1)); assertEquals("SUCCEEDED",f.state(2));
        assertEquals(2,f.count("memory_preference_evaluation")); assertEquals(2,f.count("memory_preference_transition"));
        var detail=f.details(f.block()); assertEquals("改成200万",detail.effectiveState().value().scalar());
        assertEquals(List.of(detail.history().transitions().get(0).transitionId()),detail.history().transitions().get(1).correctsTransitionIds());
        f.worker().runOnce(); f.append(List.of(message(1,"100万"),message(2,"改成200万"))); f.worker().runOnce();
        assertEquals(2,f.modelCalls.get()); assertEquals(2,f.count("memory_preference_transition"));
    }
    @Test void modelFailureRetriesWithFiniteAttemptsAndDoesNotBlockArchive() throws Exception {
        var f=new Processing(); f.append(List.of(message(1,"100万"))); f.evaluation=e -> { throw new RuntimeException("do not persist provider body or secret"); };
        f.worker().runOnce(); assertEquals("RETRY",f.state(1)); assertEquals(0,f.count("memory_preference_transition"));
        assertEquals("MEMORY_UNAVAILABLE",f.jdbc.queryForObject("SELECT last_error FROM memory_processing_job",String.class));
        f.append(List.of(message(2,"继续对话"))); assertEquals(2,f.read().messages().size());
        for(int i=0;i<4;i++) { f.due(); f.worker().runOnce(); }
        assertEquals("FAILED",f.state(1)); assertEquals(8,f.modelCalls.get());
    }
    @Test void savedAssessmentSurvivesRestartAndModelVersionChangeWithoutSecondModelCall() throws Exception {
        var f=new Processing(); f.append(List.of(message(1,"100万"))); var worker=f.worker(); worker.assess(f.claim(worker));
        assertEquals("ASSESSED",f.state(1)); assertEquals(0,f.count("memory_preference_transition"));
        f.restart(); f.restartPreferences(); f.version="fixture-eval-v2"; f.worker().runOnce();
        assertEquals(1,f.modelCalls.get()); assertEquals("SUCCEEDED",f.state(1));
        assertEquals("fixture-eval-v1",f.details(f.block()).history().transitions().get(0).extractionVersion());
    }
    @Test void expiredLeaseCanBeTakenOverAndStaleWorkerCannotCallModelOrPublish() throws Exception {
        var f=new Processing(); f.append(List.of(message(1,"100万"))); var first=f.worker(); var claim=f.claim(first);
        f.jdbc.update("UPDATE memory_processing_job SET lease_until=?",LocalDateTime.of(2020,1,1,0,0));
        f.worker().runOnce(); first.assess(claim);
        assertEquals("SUCCEEDED",f.state(1),() -> f.jdbc.queryForList("SELECT state,attempts,last_error FROM memory_processing_job").toString());
        assertEquals(1,f.modelCalls.get()); assertEquals(1,f.count("memory_preference_transition"));
    }
    @Test void takeoverWhileFirstModelRunsRejectsLateResult() throws Exception {
        var f=new Processing(); f.append(List.of(message(1,"100万")));
        f.evaluation=e -> {
            if(f.modelCalls.get()==1) {
                f.jdbc.update("UPDATE memory_processing_job SET lease_until=?",LocalDateTime.of(2020,1,1,0,0));
                f.worker().runOnce();
            }
            return f.normal(e);
        };
        f.worker().runOnce(); assertEquals(2,f.modelCalls.get()); assertEquals(1,f.count("memory_preference_evaluation"));
        assertEquals(1,f.count("memory_preference_transition")); assertEquals("SUCCEEDED",f.state(1));
    }
    @Test void withdrawalDuringModelCallPreventsReceiptAndConsolidation() throws Exception {
        var f=new Processing(); f.append(List.of(message(1,"100万"))); f.evaluation=e -> { f.withdraw(false); return f.normal(e); };
        f.worker().runOnce(); assertEquals("CANCELLED",f.state(1)); assertEquals(0,f.count("memory_preference_evaluation"));
        assertEquals(0,f.count("memory_preference_transition"));
    }
    @Test void deletionErasesAssessmentBodyAndPreventsRestartPublication() throws Exception {
        var f=new Processing(); f.append(List.of(message(1,"100万"))); var worker=f.worker(); worker.assess(f.claim(worker));
        f.withdraw(true); assertNull(f.jdbc.queryForObject("SELECT result_json FROM memory_preference_evaluation",String.class));
        f.worker().runOnce(); assertEquals("CANCELLED",f.state(1)); assertEquals(0,f.count("memory_preference_transition"));
    }
    @Test void shadowOnlyPersistsAssessmentAndNeverCreatesProductionPreferenceOrIndexJob() throws Exception {
        var f=new Processing(); f.processing.setMode(MemoryPreferenceProcessingProperties.Mode.SHADOW); f.append(List.of(message(1,"100万")));
        f.worker().runOnce(); assertEquals("SHADOWED",f.state(1)); assertEquals(1,f.count("memory_preference_evaluation"));
        assertEquals(0,f.count("memory_preference_block")); assertEquals(1,f.count("memory_processing_job"));
    }
    @Test void workerHasNoUserPermissionRequirementButCurrentServiceScopeIsMandatory() throws Exception {
        var f=new Processing(); f.append(List.of(message(1,"100万"))); int checks=f.auth.calls; f.auth.allowed=false;
        f.worker().runOnce(); assertEquals("SUCCEEDED",f.state(1)); assertEquals(checks,f.auth.calls);
        var denied=new Processing(); denied.append(List.of(message(1,"100万"))); denied.caller.setAllowedAgentsByDomain(Map.of());
        denied.worker().runOnce(); assertEquals(0,denied.modelCalls.get()); assertEquals("FAILED",denied.state(1));
    }
    @Test void disabledOrUnregisteredWorkerDoesNotReadSourceBodyOrCallModel() throws Exception {
        var f=new Processing(); f.append(List.of(message(1,"100万"))); f.processing.setEnabled(false); f.worker().runOnce();
        f.processing.setEnabled(true); f.processing.setAllowedAgentsByDomain(Map.of("other",Set.of("copy-agent"))); f.worker().runOnce();
        assertEquals(0,f.modelCalls.get()); assertEquals("PENDING",f.state(1));
    }
    @Test void assistantQuestionAndTaskConstraintNeverIncreasePreferenceEvidence() throws Exception {
        var f=new Processing(); f.append(List.of(build(1,MemoryMessage.Role.ASSISTANT,"复述",List.of(),List.of()),message(2,"可以吗"),message(3,"这次100万")));
        f.evaluation=e -> new PreferenceEvaluator.Result(e.message().sequence()==2?PreferenceTransition.Assessment.QUESTION:PreferenceTransition.Assessment.TASK_CONSTRAINT,List.of());
        f.worker().runOnce(); assertEquals("SKIPPED",f.state(1)); assertEquals(2,f.modelCalls.get()); assertEquals(0,f.count("memory_preference_transition"));
    }
    @Test void credentialShapedInputNeverEntersModelOrDerivedReceipt() throws Exception {
        var f=new Processing(); f.append(List.of(message(1,"password=private-fixture"))); f.worker().runOnce();
        assertEquals(0,f.modelCalls.get()); assertEquals(0,f.count("memory_preference_evaluation")); assertEquals("FAILED",f.state(1));
    }
    @Test void serviceProcessingScopeRevokedDuringModelCallPreventsPublication() throws Exception {
        var f=new Processing(); f.append(List.of(message(1,"100万")));
        f.evaluation=e -> { f.processing.setAllowedAgentsByDomain(Map.of()); return f.normal(e); };
        f.worker().runOnce(); assertEquals(1,f.modelCalls.get()); assertEquals("FAILED",f.state(1));
        assertEquals(0,f.count("memory_preference_evaluation")); assertEquals(0,f.count("memory_preference_transition"));
    }
    @Test void evaluatorCannotReplaceExactEvidenceWithAnUnrelatedSource() throws Exception {
        var f=new Processing(); f.append(List.of(message(1,"100万")));
        f.evaluation=e -> f.normal(new PreferenceEvaluator.Evidence(message(2,"forged"),e.acceptedAt(),e.dimensions()));
        f.worker().runOnce(); assertEquals("FAILED",f.state(1)); assertEquals(0,f.count("memory_preference_evaluation"));
        assertEquals(0,f.count("memory_preference_transition"));
    }
    @Test void poisonedCandidateIsIsolatedFromUnrelatedDimensionInBatch() throws Exception {
        var f=new Processing(); f.append(List.of(message(1,"100万"),message(2,"200万")));
        f.evaluation=e -> {
            var original=f.normal(e).candidates().get(0);
            return new PreferenceEvaluator.Result(PreferenceTransition.Assessment.PREFERENCE,List.of(new MemoryPreferenceAssessment(
                    e.message().sequence()==1?"budget":"floor",original.assessment(),e.message().sequence()==1?PreferenceTransition.Change.WITHDRAWAL:PreferenceTransition.Change.OBSERVATION,
                    original.polarity(),original.value(),null,null,null,null,List.of(),.8,f.version,original.source())));
        };
        f.worker().runOnce(); assertEquals("FAILED",f.state(1)); assertEquals("SUCCEEDED",f.state(2));
        assertEquals(1,f.count("memory_preference_transition")); assertEquals(2,f.modelCalls.get());
    }
}
