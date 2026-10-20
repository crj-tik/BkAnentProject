package com.bkanent.memory.config;

import com.bkanent.common.memory.*;
import com.bkanent.memory.service.PreferenceEvaluator;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit local development opt-in. Never prints credentials, source text or model output. */
@EnabledIfEnvironmentVariable(named="MEMORY_PREFERENCE_LIVE_MODEL",matches="true")
class PreferenceLiveModelTest {
    @Test void actualManagedModelDistinguishesPreferenceCorrectionQuestionAndTemporaryException() {
        var evaluator=new MemoryPreferenceProcessingConfiguration().preferenceEvaluator(JsonMapper.builder().build(),System.getenv("MEMORY_V2_PREFERENCE_MODEL"),
                System.getenv("DEEPSEEK_API_KEY"),System.getenv("DEEPSEEK_BASE_URL"));
        String[] texts={"我长期偏好总价100万以内的房子。","以后长期预算改成200万以内。","300万的可以吗？我只是问问，没有决定。","只在这次看房允许300万以内，长期预算不变。"};
        var context=new MemoryContextKey("42","preference-fixture","copy-agent","live-fixture");
        for(int i=0;i<texts.length;i++) {
            var association=new MemoryAssociation("fixture-matter",null,null,null);
            var source=new MemorySourceRef(MemorySourceRef.Kind.MESSAGE,context,"message-"+i,"event-"+i,(long)i+1,new MemoryRevision(1,"0".repeat(64),"message.v2"));
            var draft=new MemoryMessage(source.sourceId(),context,i+1,association,MemoryMessage.Role.USER,texts[i],List.of(),List.of(),source);
            var message=new MemoryMessage(draft.messageId(),context,draft.sequence(),association,draft.role(),draft.text(),List.of(),List.of(),
                    new MemorySourceRef(source.kind(),context,source.sourceId(),source.sourceEventId(),source.sourceSequence(),new MemoryRevision(1,MemoryCanonicalHash.messageHash(draft),"message.v2")));
            var result=evaluator.evaluate(new PreferenceEvaluator.Evidence(message,Instant.now(),Set.of("budget","floor","location")));
            if(i==2) { assertEquals(PreferenceTransition.Assessment.QUESTION,result.assessment()); assertTrue(result.candidates().isEmpty()); }
            else {
                assertEquals(PreferenceTransition.Assessment.PREFERENCE,result.assessment(),"sample index "+i); assertEquals(1,result.candidates().size(),"sample index "+i);
                assertEquals("budget",result.candidates().get(0).dimensionId());
                if(i==1) assertEquals(PreferenceTransition.Change.LONG_TERM_CORRECTION,result.candidates().get(0).change());
                if(i==3) assertEquals(PreferenceTransition.Change.TEMPORARY_EXCEPTION,result.candidates().get(0).change());
            }
        }
    }
}
