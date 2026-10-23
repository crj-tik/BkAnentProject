package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.memory.config.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Existing development MySQL only, random private scope and exact cleanup. No DDL or container creation. */
@EnabledIfEnvironmentVariable(named="MEMORY_ARCHIVE_TEST_MYSQL_URL",matches="jdbc:mysql:.*")
class PreferenceProcessingMySqlTest {
    @Test void actualMysqlClaimsAcrossInstancesRecoversAssessmentAndErasesDerivedBodies() throws Exception {
        var datasource=new DriverManagerDataSource(MemoryArchiveMySqlTest.mysqlUrl(),System.getenv("MEMORY_ARCHIVE_TEST_MYSQL_USER"),System.getenv("MEMORY_ARCHIVE_TEST_MYSQL_PASSWORD"));
        var jdbc=new JdbcTemplate(datasource); var mapper=JsonMapper.builder().addModule(new JavaTimeModule()).build();
        var manager=new DataSourceTransactionManager(datasource); String fixture="proc-"+UUID.randomUUID();
        var context=new MemoryContextKey("42","preference-fixture",fixture,fixture); String contextId=MemoryArchiveStore.key(context);
        String fixtureKey="fixture-"+UUID.randomUUID();
        var securityProperties=new MemoryV2SecurityProperties(); var caller=new MemoryV2SecurityProperties.TrustedCaller();
        caller.setPublicKeys(Map.of(fixtureKey, MemoryTestIdentity.encodedPublicKey())); caller.setAllowedAgentsByDomain(Map.of(context.domainId(),Set.of(context.agentId())));
        securityProperties.setTrustedCallers(Map.of("preference-fixture",caller));
        var security=new MemoryV2RequestSecurity(securityProperties,new MemoryArchiveServiceTest.AuthStub(),new MemoryV2ReplayGuard(jdbc));
        var store=new MemoryArchiveStore(jdbc,mapper); var sharing=new MemorySharingStore(jdbc,mapper); var sources=new MemorySourceAuthorizer(store,sharing,new MemoryAccessPolicy());
        var archive=new MemoryArchiveService(store,security,sources,manager); var preferences=new PreferenceHistoryService(store,security,sources,new MemoryPreferenceProperties(),manager);
        var processing=new MemoryPreferenceProcessingProperties(); processing.setEnabled(true); processing.setMode(MemoryPreferenceProcessingProperties.Mode.PUBLISH);
        processing.setAllowedAgentsByDomain(Map.of(context.domainId(),Set.of(context.agentId())));
        var calls=new AtomicInteger(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        PreferenceEvaluator evaluator=new PreferenceEvaluator() {
            public String version() { return "fixture-mysql-v1"; }
            public Result evaluate(Evidence evidence) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); calls.incrementAndGet(); entered.countDown();
                try { assertTrue(release.await(10,TimeUnit.SECONDS)); } catch(InterruptedException exception) { throw new IllegalStateException(exception); }
                return new Result(PreferenceTransition.Assessment.PREFERENCE,List.of(new MemoryPreferenceAssessment("budget",PreferenceTransition.Assessment.PREFERENCE,
                        PreferenceTransition.Change.OBSERVATION,PreferenceTransition.Polarity.NEGATIVE,MemoryJsonValue.string("100万"),null,null,null,null,List.of(),.8,version(),new MemorySourceDependency(evidence.message().source(),null))));
            }
        };
        String blockId=null;
        try {
            var original=PreferenceHistoryServiceTest.otherMessage(context,"mysql-processing-event","不超过100万");
            var permitRequest=PreferenceHistoryMySqlTest.request(MemoryV2Contract.OPERATION_RECEIVE_PERMIT,e -> new MemoryReceivePermitRequest(e,context,List.of(new MemoryReceivePermitRequest.EventSubmission(original.source().sourceEventId(),original.source().version().contentHash(),"conversation-archive"))));
            var permit=archive.requestReceivePermit(permitRequest,PreferenceHistoryMySqlTest.sign(permitRequest,fixtureKey,MemoryTestIdentity.PAIR.getPrivate()));
            var delivery=PreferenceHistoryMySqlTest.request(MemoryV2Contract.OPERATION_DELIVER_MESSAGES,e -> new MemoryDeliveryRequest(e,context,original.association(),permit.permitId(),List.of(original)));
            archive.deliverMessages(delivery,PreferenceHistoryMySqlTest.sign(delivery,fixtureKey,MemoryTestIdentity.PAIR.getPrivate()));
            var worker=new PreferenceProcessingWorker(store,sources,preferences,new MemoryPreferenceProperties(),processing,securityProperties,evaluator,manager);
            var other=new PreferenceProcessingWorker(store,sources,preferences,new MemoryPreferenceProperties(),processing,securityProperties,evaluator,manager);
            var future=CompletableFuture.runAsync(worker::runOnce);
            try { assertTrue(entered.await(10,TimeUnit.SECONDS)); other.runOnce(); assertEquals(1,calls.get()); }
            finally { release.countDown(); }
            future.get(15,TimeUnit.SECONDS);
            assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT state FROM memory_processing_job WHERE context_id=? AND job_kind='ASSESS_MESSAGE'",String.class,contextId));
            blockId=jdbc.queryForObject("SELECT block_id FROM memory_preference_block WHERE owner_id=? AND domain_id=? AND agent_id=?",String.class,context.ownerId(),context.domainId(),fixture);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM memory_preference_transition WHERE block_id=?",Integer.class,blockId));
            // Simulate a restart after durable assessment and before final receipt: same source cannot create a second transition.
            jdbc.update("UPDATE memory_processing_job SET state='ASSESSED' WHERE context_id=? AND job_kind='ASSESS_MESSAGE'",contextId);
            other.runOnce(); assertEquals(1,calls.get());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM memory_preference_transition WHERE block_id=?",Integer.class,blockId));
            var delete=PreferenceHistoryMySqlTest.request(MemoryV2Contract.OPERATION_WITHDRAW,e -> new MemoryWithdrawalRequest(e,context,original.source().sourceEventId(),true));
            archive.withdrawSource(delete,PreferenceHistoryMySqlTest.sign(delete,fixtureKey,MemoryTestIdentity.PAIR.getPrivate()));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM memory_preference_evaluation e JOIN memory_processing_job j ON j.job_id=e.job_id WHERE j.context_id=? AND e.result_json IS NOT NULL",Integer.class,contextId));
        } finally {
            release.countDown();
            if(blockId!=null) {
                jdbc.update("DELETE FROM memory_preference_revision WHERE block_id=?",blockId);
                jdbc.update("DELETE FROM memory_preference_transition WHERE block_id=?",blockId);
                jdbc.update("DELETE FROM memory_preference_block WHERE block_id=? AND agent_id=?",blockId,fixture);
            }
            jdbc.update("DELETE FROM memory_preference_evaluation WHERE job_id IN (SELECT job_id FROM memory_processing_job WHERE context_id=?)",contextId);
            jdbc.update("DELETE FROM memory_receive_event WHERE permit_id IN (SELECT permit_id FROM memory_receive_permit WHERE context_id=?)",contextId);
            jdbc.update("DELETE FROM memory_privacy_cleanup_target WHERE context_id=?",contextId);
            jdbc.update("DELETE FROM memory_privacy_deletion WHERE context_id=?",contextId);
            for(String table:List.of("memory_message_observation","memory_receive_permit","memory_message","memory_processing_job")) jdbc.update("DELETE FROM "+table+" WHERE context_id=?",contextId);
            jdbc.update("DELETE FROM memory_source_dependency WHERE target_context_id=? OR source_context_id=?",contextId,contextId);
            jdbc.update("DELETE FROM memory_conversation WHERE context_id=? AND agent_id=?",contextId,fixture);
            jdbc.update("DELETE FROM memory_v2_request_nonce WHERE key_id=?",fixtureKey);
        }
    }
}
