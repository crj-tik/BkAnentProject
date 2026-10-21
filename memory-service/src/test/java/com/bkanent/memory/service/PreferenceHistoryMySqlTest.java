package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.bkanent.memory.config.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** Existing development MySQL only. No DDL or container creation; exact random owner/domain/agent fixture cleanup. */
@EnabledIfEnvironmentVariable(named="MEMORY_ARCHIVE_TEST_MYSQL_URL",matches="jdbc:mysql:.*")
class PreferenceHistoryMySqlTest {
    @Test void realMysqlFixedRevisionHistoryIdempotenceAndDeletion() throws Exception {
        var datasource=new DriverManagerDataSource(MemoryArchiveMySqlTest.mysqlUrl(),System.getenv("MEMORY_ARCHIVE_TEST_MYSQL_USER"),System.getenv("MEMORY_ARCHIVE_TEST_MYSQL_PASSWORD"));
        var jdbc=new JdbcTemplate(datasource); var mapper=JsonMapper.builder().addModule(new JavaTimeModule()).build();
        var manager=new DataSourceTransactionManager(datasource); String fixture="pref-"+UUID.randomUUID();
        var context=new MemoryContextKey("42","preference-fixture",fixture,fixture); String contextId=MemoryArchiveStore.key(context);
        String fixtureKey="fixture-"+UUID.randomUUID(), secret=UUID.randomUUID().toString()+UUID.randomUUID();
        var securityProperties=new MemoryV2SecurityProperties(); var caller=new MemoryV2SecurityProperties.TrustedCaller();
        caller.setKeyId(fixtureKey); caller.setSigningSecret(secret); caller.setAllowedAgentsByDomain(Map.of(context.domainId(),Set.of(context.agentId())));
        securityProperties.setTrustedCallers(Map.of("preference-fixture",caller));
        var security=new MemoryV2RequestSecurity(securityProperties,new MemoryArchiveServiceTest.AuthStub(),new MemoryV2ReplayGuard(jdbc));
        var store=new MemoryArchiveStore(jdbc,mapper); var sharing=new MemorySharingStore(jdbc,mapper); var sources=new MemorySourceAuthorizer(store,sharing,new MemoryAccessPolicy());
        var preferences=new PreferenceHistoryService(store,security,sources,new MemoryPreferenceProperties(),manager);
        var archive=new MemoryArchiveService(store,security,sources,preferences,manager);
        String blockId=null;
        try {
            var original=PreferenceHistoryServiceTest.otherMessage(context,"mysql-pref-event","exclude expensive");
            var permitRequest=request(MemoryV2Contract.OPERATION_RECEIVE_PERMIT,e -> new MemoryReceivePermitRequest(e,context,List.of(new MemoryReceivePermitRequest.EventSubmission(original.source().sourceEventId(),original.source().version().contentHash(),"conversation-archive"))));
            var permit=archive.requestReceivePermit(permitRequest,sign(permitRequest,fixtureKey,secret));
            var delivery=request(MemoryV2Contract.OPERATION_DELIVER_MESSAGES,e -> new MemoryDeliveryRequest(e,context,original.association(),permit.permitId(),List.of(original)));
            archive.deliverMessages(delivery,sign(delivery,fixtureKey,secret));
            var candidate=new MemoryPreferenceAssessment("budget",PreferenceTransition.Assessment.PREFERENCE,PreferenceTransition.Change.OBSERVATION,PreferenceTransition.Polarity.NEGATIVE,
                    MemoryJsonValue.number("100"),null,null,null,null,List.of(),.8,"fixture-v1",new MemorySourceDependency(original.source(),null));
            var write=request(MemoryV2Contract.OPERATION_PREFERENCE_WRITE,e -> new MemoryPreferenceWriteRequest(e,context,0,candidate));
            var first=preferences.submit(write,sign(write,fixtureKey,secret)); blockId=first.blockId(); assertEquals(first,preferences.submit(write,sign(write,fixtureKey,secret)));

            var writerRequest=request(MemoryV2Contract.OPERATION_WRITER,e -> new MemoryWriterRequest(e,context,MemoryWriterRequest.Action.ACQUIRE,"run-1",0));
            var writer=archive.manageWriter(writerRequest,sign(writerRequest,fixtureKey,secret)).authority();
            var intent=new MemoryMatterIntent(MemoryMatterIntent.Action.START,null,0,"买房预算");
            var matterMessage=MemoryMatterServiceTest.message(context,2,intent);
            var matterPermitRequest=request(MemoryV2Contract.OPERATION_RECEIVE_PERMIT,e -> new MemoryReceivePermitRequest(e,context,
                    List.of(new MemoryReceivePermitRequest.EventSubmission(matterMessage.source().sourceEventId(),matterMessage.source().version().contentHash(),"conversation-archive")),List.of(),writer));
            var matterPermit=archive.requestReceivePermit(matterPermitRequest,sign(matterPermitRequest,fixtureKey,secret));
            var matterDelivery=request(MemoryV2Contract.OPERATION_DELIVER_MESSAGES,e -> new MemoryDeliveryRequest(e,context,matterMessage.association(),matterPermit.permitId(),List.of(matterMessage)));
            archive.deliverMessages(matterDelivery,sign(matterDelivery,fixtureKey,secret));
            var beforeMatter=request(MemoryV2Contract.OPERATION_WORKING_SET,e -> new MemoryWorkingSetRequest(e,context));
            long expectedConversationRevision=archive.readWorkingSet(beforeMatter,sign(beforeMatter,fixtureKey,secret)).version().revision();
            var matterRequest=request(MemoryV2Contract.OPERATION_MATTER_WRITE,e -> new MemoryMatterWriteRequest(e,context,intent,
                    new MemorySourceDependency(matterMessage.source(),null),expectedConversationRevision,writer));
            var matter=new MemoryMatterService(store,security,sources,manager).transition(matterRequest,sign(matterRequest,fixtureKey,secret)).matter();
            var assemblyRequest=request(MemoryV2Contract.OPERATION_ASSEMBLE_PREFERENCES,e -> new MemoryPreferenceAssemblyRequest(e,context,matter.matterId(),800));
            var assembled=preferences.assemble(assemblyRequest,sign(assemblyRequest,fixtureKey,secret));
            assertEquals(List.of("budget"),assembled.matches().stream().map(MemoryPreferenceMatch::dimensionId).toList());
            assertEquals("100",assembled.matches().get(0).effectiveState().value().scalar());
            var latestRequest=request(MemoryV2Contract.OPERATION_WORKING_SET,e -> new MemoryWorkingSetRequest(e,context));
            var latest=archive.readWorkingSet(latestRequest,sign(latestRequest,fixtureKey,secret));
            var boundary=request(MemoryV2Contract.OPERATION_BOUNDARY,e -> new MemoryBoundaryRequest(e,context,latest.version(),List.of(),assembled.selections()));
            assertTrue(archive.checkBoundary(boundary,sign(boundary,fixtureKey,secret)).unchanged());

            var accepted=first;
            Instant asOf=Instant.now();
            var read=request(MemoryV2Contract.OPERATION_PREFERENCE_DETAIL,e -> new MemoryPreferenceDetailRequest(e,context,accepted.blockId(),null,1,asOf,null));
            var view=preferences.read(read,sign(read,fixtureKey,secret)); assertEquals(PreferenceTransition.Polarity.NEGATIVE,view.effectiveState().polarity());
            assertEquals(PreferenceHistoryPage.Completeness.COMPLETE,view.history().completeness());
            assertEquals(view.history().transitions().get(0).occurredAt(),MemoryArchiveStore.utcInstant(jdbc.queryForObject(
                    "SELECT accepted_at FROM memory_message_observation WHERE context_id=? AND source_event_id=?",
                    Object.class,contextId,original.source().sourceEventId())));
            var plan=jdbc.queryForList("EXPLAIN SELECT t.transition_id FROM memory_preference_transition t JOIN memory_message_observation o "
                    + "ON o.context_id=t.source_context_id AND o.source_event_id=t.source_event_id JOIN memory_message m ON m.context_id=t.source_context_id AND m.source_event_id=t.source_event_id "
                    + "WHERE t.block_id=? AND t.introduced_revision<=? AND (o.accepted_at,t.source_context_id,m.sequence_no,t.transition_id)>(?,?,?,?) "
                    + "ORDER BY o.accepted_at,t.source_context_id,m.sequence_no,t.transition_id LIMIT 2",blockId,1,java.time.LocalDateTime.ofInstant(view.history().transitions().get(0).occurredAt(),java.time.ZoneOffset.UTC),contextId,1,first.transitionId());
            assertFalse(plan.isEmpty());
            assertTrue(plan.stream().anyMatch(row -> row.get("key")!=null));
            var delete=request(MemoryV2Contract.OPERATION_WITHDRAW,e -> new MemoryWithdrawalRequest(e,context,original.source().sourceEventId(),true));
            archive.withdrawSource(delete,sign(delete,fixtureKey,secret));
            assertEquals(MemoryErrorCode.DELETED,assertThrows(MemoryException.class,() -> preferences.read(read,sign(read,fixtureKey,secret))).code());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM memory_preference_transition WHERE block_id=? AND payload_json IS NOT NULL",Integer.class,blockId));
        } finally {
            if(blockId!=null) {
                jdbc.update("DELETE FROM memory_preference_revision WHERE block_id=?",blockId);
                jdbc.update("DELETE FROM memory_preference_transition WHERE block_id=?",blockId);
                jdbc.update("DELETE FROM memory_preference_block WHERE block_id=? AND agent_id=?",blockId,fixture);
            }
            jdbc.update("DELETE FROM memory_receive_event WHERE permit_id IN (SELECT permit_id FROM memory_receive_permit WHERE context_id=?)",contextId);
            jdbc.update("DELETE FROM memory_privacy_cleanup_target WHERE context_id=?",contextId);
            jdbc.update("DELETE FROM memory_privacy_deletion WHERE context_id=?",contextId);
            for(String table:List.of("memory_matter_receipt","memory_matter_revision","memory_matter","memory_conversation_writer",
                    "memory_message_observation","memory_receive_permit","memory_message","memory_processing_job"))
                jdbc.update("DELETE FROM "+table+" WHERE context_id=?",contextId);
            jdbc.update("DELETE FROM memory_source_dependency WHERE target_context_id=? OR source_context_id=?",contextId,contextId);
            jdbc.update("DELETE FROM memory_conversation WHERE context_id=? AND agent_id=?",contextId,fixture);
            jdbc.update("DELETE FROM memory_v2_request_nonce WHERE key_id=?",fixtureKey);
        }
    }
    static <T extends MemoryV2Request>T request(String operation,Function<MemoryRequestEnvelope,T> factory) {
        String id=UUID.randomUUID().toString(); var draft=factory.apply(new MemoryRequestEnvelope(operation,"preference-fixture","memory-service",id,"0".repeat(64),2));
        return factory.apply(new MemoryRequestEnvelope(operation,"preference-fixture","memory-service",id,MemoryCanonicalHash.requestBodyHash(draft),2));
    }
    static MemoryRequestProof sign(MemoryV2Request request,String key,String secret) {
        long now=Instant.now().getEpochSecond(); return MemoryRequestSigner.sign(request,new AuthenticatedPrincipal(42L),key,now,now+60,UUID.randomUUID().toString().replace("-",""),secret.getBytes(StandardCharsets.UTF_8));
    }
}
