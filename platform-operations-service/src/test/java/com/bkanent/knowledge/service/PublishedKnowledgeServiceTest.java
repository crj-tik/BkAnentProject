package com.bkanent.knowledge.service;

import com.bkanent.common.a2a.VerifiedKnowledgeContext;
import com.bkanent.common.knowledge.KnowledgeRef;
import com.bkanent.common.knowledge.KnowledgeContract;
import com.bkanent.common.knowledge.PublishedSearchRequest;
import com.bkanent.knowledge.domain.KnowledgeCaseRecord;
import com.bkanent.knowledge.domain.KnowledgeRevisionRecord;
import com.bkanent.knowledge.domain.KnowledgeSourceRecord;
import com.bkanent.knowledge.domain.KnowledgeState;
import com.bkanent.knowledge.domain.KnowledgeOperationException;
import com.bkanent.knowledge.persistence.KnowledgeRepository;
import com.bkanent.knowledge.security.KnowledgeOperations;
import com.bkanent.knowledge.source.InterviewTranscriptSource;
import com.bkanent.knowledge.source.SourceBinding;
import com.bkanent.knowledge.source.SourceState;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublishedKnowledgeServiceTest {
    private final KnowledgeRepository repository = mock(KnowledgeRepository.class);
    private final InterviewTranscriptSource source = mock(InterviewTranscriptSource.class);
    private final PublishedKnowledgeService service = new PublishedKnowledgeService(repository, source);

    @Test
    void validNoMatchIsAnEmptySuccessfulResult() {
        when(repository.search("OWN", "1", null, null, null, List.of(), 0, 21)).thenReturn(List.of());

        try (var ignored = VerifiedKnowledgeContext.open(List.of(KnowledgeOperations.READ_OWN), Map.of())) {
            var result = service.search(new PublishedSearchRequest(KnowledgeContract.VERSION, null, null,
                    List.of(), null, 20));
            assertThat(result.items()).isEmpty();
        }
    }

    @Test
    void sourceViewFailureIsNotReportedAsNoMatchAndNeverReturnsBody() {
        String contentHash = "a".repeat(64);
        SourceBinding binding = new SourceBinding("101", "b".repeat(64), "c".repeat(64),
                1, 1, 1, "UNKNOWN");
        KnowledgeCaseRecord knowledge = new KnowledgeCaseRecord("knowledge-1", "OWN", "1", null,
                "跟进", "客户没有反馈", "带看", List.of(), KnowledgeState.PUBLISHED, 1, 1,
                "UNKNOWN", 0, OffsetDateTime.now(), OffsetDateTime.now());
        KnowledgeRevisionRecord revision = new KnowledgeRevisionRecord(1, "knowledge-1", 1, contentHash,
                "summary", null, "UNKNOWN", "UNVERIFIED", "d".repeat(64), "rules-v1", "1", OffsetDateTime.now());
        when(repository.search("OWN", "1", null, null, null, List.of(), 0, 21)).thenReturn(List.of(
                new KnowledgeRepository.KnowledgeSearchRow("knowledge-1", 1, contentHash, "summary",
                        "UNKNOWN", OffsetDateTime.now())));
        when(repository.findCase("knowledge-1", false)).thenReturn(knowledge);
        when(repository.findRevision("knowledge-1", 1)).thenReturn(revision);
        when(repository.findSources("knowledge-1", 1)).thenReturn(List.of(new KnowledgeSourceRecord(binding,
                "e".repeat(64), 0, 3)));
        when(source.check("101", binding)).thenReturn(SourceState.UNAVAILABLE);

        try (var ignored = VerifiedKnowledgeContext.open(List.of(KnowledgeOperations.READ_OWN), Map.of())) {
            assertThatThrownBy(() -> service.search(new PublishedSearchRequest(KnowledgeContract.VERSION,
                    null, null, List.of(), null, 20)))
                    .isInstanceOfSatisfying(KnowledgeOperationException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
                        assertThat(failure.status()).isEqualTo(503);
                    });

            var result = service.batchRead(List.of(new KnowledgeRef("knowledge-1", "r1", contentHash)));
            assertThat(result.items()).hasSize(1);
            assertThat(result.items().get(0).status()).isEqualTo("UNAVAILABLE");
            assertThat(result.items().get(0).body()).isNull();
            assertThat(result.items().get(0).sourceRefs()).isEmpty();
        }
    }
}
