package com.bkanent.knowledge.service;

import com.bkanent.common.a2a.VerifiedKnowledgeContext;
import com.bkanent.common.knowledge.KnowledgeBody;
import com.bkanent.knowledge.api.GenerateKnowledgeRequest;
import com.bkanent.knowledge.api.KnowledgeGenerationStatus;
import com.bkanent.knowledge.domain.KnowledgeCaseRecord;
import com.bkanent.knowledge.domain.KnowledgeOperationException;
import com.bkanent.knowledge.domain.KnowledgeState;
import com.bkanent.knowledge.generation.CandidateEvidence;
import com.bkanent.knowledge.generation.KnowledgeCandidate;
import com.bkanent.knowledge.generation.KnowledgeCandidateGenerator;
import com.bkanent.knowledge.persistence.KnowledgeRepository;
import com.bkanent.knowledge.security.KnowledgeOperations;
import com.bkanent.knowledge.source.InterviewSourceSnapshot;
import com.bkanent.knowledge.source.InterviewTranscriptSource;
import com.bkanent.knowledge.source.SourceBinding;
import com.bkanent.knowledge.source.SourceState;
import com.bkanent.knowledge.source.TranscriptIntegrity;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeGenerationServiceTest {
    @Test
    void completedRequestRetryAfterServiceRestartReusesStatusAndRejectsDifferentContent() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        InterviewTranscriptSource sources = mock(InterviewTranscriptSource.class);
        KnowledgeCandidateGenerator generator = mock(KnowledgeCandidateGenerator.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        doNothing().when(transactions).commit(any());
        doNothing().when(transactions).rollback(any());

        String originalText = "先问清客户关注点，再安排第二次带看。";
        SourceBinding binding = new SourceBinding("101", TranscriptIntegrity.sha256("snapshot"),
                TranscriptIntegrity.sha256(originalText), 1, 1, 1, "UNKNOWN");
        InterviewSourceSnapshot source = new InterviewSourceSnapshot(binding, "客户跟进", originalText, "L2", "UPLOAD");
        when(sources.read("101")).thenReturn(source);
        when(sources.check("101", binding)).thenReturn(SourceState.CURRENT);

        KnowledgeCaseRecord record = new KnowledgeCaseRecord("knowledge-1", "OWN", "1", null,
                "跟进", "客户没有反馈", "带看", List.of(), KnowledgeState.DRAFT, 1, null,
                "UNKNOWN", 0, OffsetDateTime.now(), OffsetDateTime.now());
        when(repository.findCase("knowledge-1", false)).thenReturn(record);
        when(repository.findCase("knowledge-1", true)).thenReturn(record);

        GenerateKnowledgeRequest request = new GenerateKnowledgeRequest("request-1", 0L,
                List.of("101"), "rules-v1");
        String requestJson = "{\"requestId\":\"request-1\"}";
        when(repository.write(request)).thenReturn(requestJson);
        when(repository.write(any(Map.class))).thenReturn("{\"input\":\"stable\"}");
        when(repository.write(any(KnowledgeGenerationStatus.class))).thenReturn("saved-status-json");
        Map<String, Object> existing = new HashMap<>();
        when(repository.findGenerationTask("request-1")).thenReturn(null, null, existing);

        KnowledgeCandidate candidate = new KnowledgeCandidate(
                new KnowledgeBody("客户没有反馈", "带看", List.of(), List.of("确认关注点"),
                        List.of(), List.of(), List.of("转化结果待核验"), "UNVERIFIED"),
                "先确认关注点再跟进", List.of(new CandidateEvidence("101", "先问清客户关注点")));
        when(generator.generate(List.of(source))).thenReturn(candidate);

        KnowledgeGenerationService service = new KnowledgeGenerationService(repository, sources, generator, transactions);
        KnowledgeGenerationStatus generated;
        try (var ignored = VerifiedKnowledgeContext.open(List.of(KnowledgeOperations.GENERATE_OWN), Map.of())) {
            generated = service.generate("knowledge-1", request);
            existing.put("request_hash", TranscriptIntegrity.sha256(requestJson));
            existing.put("created_by_actor_id", "1");
            existing.put("knowledge_id", "knowledge-1");
            existing.put("task_id", generated.taskId());
            existing.put("status", "SUCCEEDED");
            existing.put("result_json", "saved-status-json");
            when(repository.read(eq("saved-status-json"), eq(KnowledgeGenerationStatus.class)))
                    .thenReturn(generated);
            KnowledgeGenerationService restartedService = new KnowledgeGenerationService(
                    repository, sources, generator, transactions);
            KnowledgeGenerationStatus retried = restartedService.generate("knowledge-1", request);
            assertThat(retried).isEqualTo(generated);

            GenerateKnowledgeRequest differentContent = new GenerateKnowledgeRequest("request-1", 0L,
                    List.of("101"), "rules-v2");
            when(repository.write(differentContent)).thenReturn("{\"rulesVersion\":\"rules-v2\"}");
            assertThatThrownBy(() -> restartedService.generate("knowledge-1", differentContent))
                    .isInstanceOfSatisfying(KnowledgeOperationException.class,
                            failure -> assertThat(failure.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        }

        assertThat(generated.status()).isEqualTo("SUCCEEDED");
        assertThat(generated.toString()).doesNotContain(originalText);
        verify(generator, times(1)).generate(List.of(source));
        verify(sources, times(1)).read("101");
        verify(repository, times(4)).findGenerationTask("request-1");
    }
}
