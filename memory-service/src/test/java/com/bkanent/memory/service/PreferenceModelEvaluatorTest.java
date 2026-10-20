package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreferenceModelEvaluatorTest {
    static String item(String change,String polarity,String value,String quote,String until) {
        return "{\"dimension\":\"budget\",\"change\":\""+change+"\",\"polarity\":\""+polarity+"\",\"value\":\""+value
                +"\",\"evidence\":\""+quote+"\",\"applicability\":null,\"effectiveUntil\":"+(until==null?"null":"\""+until+"\"")+",\"confidence\":0.8}";
    }
    static String output(String assessment,String subject,String items) { return "{\"assessment\":\""+assessment+"\",\"subject\":\""+subject+"\",\"preferences\":["+items+"]}"; }
    static class Fixture {
        final ChatModel model=mock(ChatModel.class);
        final PreferenceModelEvaluator evaluator=new PreferenceModelEvaluator(model,"fixture-model",JsonMapper.builder().build());
        PreferenceEvaluator.Result evaluate(String text,String json) {
            when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(json),ChatGenerationMetadata.builder().finishReason("stop").build()))));
            return evaluator.evaluate(new PreferenceEvaluator.Evidence(MemoryArchiveServiceTest.message(1,text),Instant.parse("2026-10-20T00:00:00Z"),Set.of("budget","floor")));
        }
    }
    @Test void extractsGroundedNegativeWithServerOwnedEvidenceAndVersion() {
        var f=new Fixture(); var result=f.evaluate("我不接受100万以上",output("PREFERENCE","SELF",item("OBSERVATION","NEGATIVE","100万以上","不接受100万以上",null)));
        var candidate=result.candidates().get(0); assertEquals(PreferenceTransition.Polarity.NEGATIVE,candidate.polarity());
        assertEquals("100万以上",candidate.value().scalar()); assertEquals(f.evaluator.version(),candidate.extractionVersion());
        assertEquals(MemoryArchiveServiceTest.message(1,"我不接受100万以上").source(),candidate.source().source());
        assertTrue(candidate.correctsTransitionIds().isEmpty());
    }
    @Test void separatesQuestionsTaskConstraintsOtherSubjectsAndUncertainSubject() {
        for(String kind:List.of("QUESTION","TASK_CONSTRAINT","OTHER")) {
            var f=new Fixture(); assertTrue(f.evaluate("100万可以吗",output(kind,"SELF","")).candidates().isEmpty());
        }
        for(String subject:List.of("OTHER","UNKNOWN")) {
            var f=new Fixture(); assertEquals(PreferenceTransition.Assessment.OTHER,f.evaluate("客户喜欢100万",output("PREFERENCE",subject,"")).assessment());
        }
    }
    @Test void preservesExplicitCorrectionAndAnchoredTemporaryException() {
        var f=new Fixture(); var correction=f.evaluate("以后预算改成200万",output("PREFERENCE","SELF",item("LONG_TERM_CORRECTION","AFFIRMATIVE","200万","改成200万",null)));
        assertEquals(PreferenceTransition.Change.LONG_TERM_CORRECTION,correction.candidates().get(0).change());
        var temporary=f.evaluate("这次接受100万到2026-11-01T00:00:00Z",output("PREFERENCE","SELF",item("TEMPORARY_EXCEPTION","AFFIRMATIVE","100万","接受100万", "2026-11-01T00:00:00Z")));
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"),temporary.candidates().get(0).effectiveUntil());
        assertEquals(MemoryArchiveServiceTest.ASSOCIATION.matterId(),temporary.candidates().get(0).matterId());
    }
    @Test void rejectsUnknownKeysDuplicateFieldsTrailingContentAndUngroundedValues() {
        String good=output("PREFERENCE","SELF",item("OBSERVATION","AFFIRMATIVE","100万","100万",null));
        for(String json:List.of(good.replace("\"subject\":\"SELF\"","\"subject\":\"SELF\",\"ownerId\":\"other\""),
                good.replace("\"assessment\":\"PREFERENCE\"","\"assessment\":\"PREFERENCE\",\"assessment\":\"QUESTION\""),good+" {}",
                good.replace("100万","999万"),good.replace("budget","invented"),good.replace("0.8","2.0"),
                output("QUESTION","SELF",item("OBSERVATION","AFFIRMATIVE","100万","100万",null)))) {
            var f=new Fixture(); assertEquals(MemoryErrorCode.INVALID_EXTRACTION,assertThrows(MemoryException.class,() -> f.evaluate("100万",json)).code());
        }
    }
    @Test void rejectsUnanchoredDatesAndDuplicateDimensions() {
        String one=item("OBSERVATION","AFFIRMATIVE","100万","100万",null);
        for(String json:List.of(output("PREFERENCE","SELF",one+","+one),output("PREFERENCE","SELF",item("TEMPORARY_EXCEPTION","AFFIRMATIVE","100万","100万","2030-01-01T00:00:00Z")))) {
            var f=new Fixture(); assertEquals(MemoryErrorCode.INVALID_EXTRACTION,assertThrows(MemoryException.class,() -> f.evaluate("100万",json)).code());
        }
    }
    @Test void incompleteModelOutputAndCredentialsAreRejected() {
        var f=new Fixture(); when(f.model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(output("OTHER","UNKNOWN","")),ChatGenerationMetadata.builder().finishReason("length").build()))));
        assertThrows(MemoryException.class,() -> f.evaluator.evaluate(new PreferenceEvaluator.Evidence(MemoryArchiveServiceTest.message(1,"预算"),Instant.now(),Set.of("budget"))));
        var credential=new Fixture(); assertThrows(MemoryException.class,() -> credential.evaluate("api_key=synthetic-fixture",output("OTHER","UNKNOWN","")));
        verifyNoInteractions(credential.model);
    }
    @Test void providerErrorDoesNotLeakProviderMessageIntoPublicFailure() {
        var f=new Fixture(); when(f.model.call(any(Prompt.class))).thenThrow(new IllegalStateException("fixture-provider-private-data"));
        var error=assertThrows(MemoryException.class,() -> f.evaluator.evaluate(new PreferenceEvaluator.Evidence(MemoryArchiveServiceTest.message(1,"100万"),Instant.now(),Set.of("budget"))));
        assertEquals(MemoryErrorCode.MEMORY_UNAVAILABLE,error.code()); assertFalse(error.getMessage().contains("fixture-provider"));
    }
}
