package com.bkanent.knowledge.service;

import com.bkanent.common.a2a.VerifiedKnowledgeContext;
import com.bkanent.common.knowledge.KnowledgeBody;
import com.bkanent.knowledge.api.EditKnowledgeRequest;
import com.bkanent.knowledge.api.KnowledgeOperationRequest;
import com.bkanent.knowledge.api.KnowledgeMutationReceipt;
import com.bkanent.knowledge.api.KnowledgeReviewRequest;
import com.bkanent.knowledge.domain.KnowledgeCaseRecord;
import com.bkanent.knowledge.domain.KnowledgeRevisionRecord;
import com.bkanent.knowledge.domain.KnowledgeOperationException;
import com.bkanent.knowledge.domain.KnowledgeState;
import com.bkanent.knowledge.persistence.KnowledgeRepository;
import com.bkanent.knowledge.security.KnowledgeOperations;
import com.bkanent.knowledge.source.InterviewTranscriptSource;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class KnowledgeLifecycleServiceTest {
    @Test
    void differentAuthorizedReviewerCanApproveOwnPendingCase() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        InterviewTranscriptSource source = mock(InterviewTranscriptSource.class);
        KnowledgeLifecycleService service = new KnowledgeLifecycleService(repository, source);
        when(repository.findCase("knowledge-1", true)).thenReturn(caseRecord());
        when(repository.findReceipt("review-request", KnowledgeOperations.REVIEW_OWN)).thenReturn(null);
        when(repository.write(org.mockito.ArgumentMatchers.any())).thenReturn("request-json");

        try (var ignored = VerifiedKnowledgeContext.open(List.of(KnowledgeOperations.REVIEW_OWN), Map.of(), "7")) {
            var receipt = service.review("knowledge-1",
                    new KnowledgeReviewRequest("review-request", 0L, "APPROVE", "核验通过"));
            assertThat(receipt.state()).isEqualTo("REVIEW_PENDING");
        }

        verify(repository).recordReview("knowledge-1", 1, "7", "APPROVE", "核验通过");
        verify(repository).transition("knowledge-1", 0L, KnowledgeState.REVIEW_PENDING,
                KnowledgeState.REVIEW_PENDING, null);
    }

    @Test
    void editCreatesANewRevisionAndCarriesForwardSourceReferencesAndDataMode() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        InterviewTranscriptSource source = mock(InterviewTranscriptSource.class);
        KnowledgeLifecycleService service = new KnowledgeLifecycleService(repository, source);
        KnowledgeCaseRecord existing = new KnowledgeCaseRecord("knowledge-1", "OWN", "1", null,
                "跟进", "客户没反馈", "带看", List.of(), KnowledgeState.WITHDRAWN, 1, 1,
                "SIMULATED", 0, OffsetDateTime.now(), OffsetDateTime.now());
        EditKnowledgeRequest request = new EditKnowledgeRequest("request-edit", 0L, "跟进方案",
                "客户没反馈", "带看", List.of("回访"), new KnowledgeBody("客户没反馈", "带看",
                List.of("已完成带看"), List.of("约定回访时间"), List.of("结果待核实"),
                List.of("客户需要时间"), List.of("转化效果未核验"), "SELF_REPORTED"));
        when(repository.findCase("knowledge-1", true)).thenReturn(existing);
        when(repository.findReceipt("request-edit", KnowledgeOperations.EDIT_OWN)).thenReturn(null);
        when(repository.write(request)).thenReturn("request-json");
        when(repository.write(any(KnowledgeMutationReceipt.class))).thenReturn("receipt-json");

        try (var ignored = VerifiedKnowledgeContext.open(List.of(KnowledgeOperations.EDIT_OWN), Map.of())) {
            service.edit("knowledge-1", request);
        }

        verify(repository).copySources("knowledge-1", 1, 2);
        verify(repository).insertRevision(eq("knowledge-1"), eq(2), anyString(), eq(""), any(KnowledgeBody.class),
                eq("SIMULATED"), eq("UNVERIFIED"), anyString(), eq("manual-v1"), eq("1"));
        verify(repository).updateCaseContent("knowledge-1", 0L, "跟进方案", "客户没反馈", "带看",
                List.of("回访"), 2, "SIMULATED");
    }

    @Test
    void editRequestReceiptCannotBeReplayedForAnotherKnowledgeTarget() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        InterviewTranscriptSource source = mock(InterviewTranscriptSource.class);
        KnowledgeLifecycleService service = new KnowledgeLifecycleService(repository, source);
        KnowledgeCaseRecord other = new KnowledgeCaseRecord("knowledge-2", "OWN", "1", null,
                "跟进", "客户没反馈", "带看", List.of(), KnowledgeState.WITHDRAWN, 1, null,
                "UNKNOWN", 0, OffsetDateTime.now(), OffsetDateTime.now());
        EditKnowledgeRequest request = new EditKnowledgeRequest("request-edit", 0L, "跟进方案",
                "客户没反馈", "带看", List.of("回访"), new KnowledgeBody("客户没反馈", "带看",
                List.of("已完成带看"), List.of("约定回访时间"), List.of("结果待核实"),
                List.of("客户需要时间"), List.of("转化效果未核验"), "SELF_REPORTED"));
        when(repository.findCase("knowledge-2", true)).thenReturn(other);
        when(repository.write(request)).thenReturn("request-json");
        when(repository.findReceipt("request-edit", KnowledgeOperations.EDIT_OWN)).thenReturn(Map.of(
                "request_hash", com.bkanent.knowledge.source.TranscriptIntegrity.sha256("request-json"),
                "actor_id", "1", "target_id", "knowledge-1", "response_json", "{}"));

        try (var ignored = VerifiedKnowledgeContext.open(List.of(KnowledgeOperations.EDIT_OWN), Map.of())) {
            assertThatThrownBy(() -> service.edit("knowledge-2", request))
                    .isInstanceOfSatisfying(KnowledgeOperationException.class,
                            failure -> assertThat(failure.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        }

        verify(repository, never()).reserveReceipt(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void ownerCannotApproveTheirOwnCase() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        InterviewTranscriptSource source = mock(InterviewTranscriptSource.class);
        KnowledgeLifecycleService service = new KnowledgeLifecycleService(repository, source);
        when(repository.findCase("knowledge-1", true)).thenReturn(caseRecord());
        when(repository.write(org.mockito.ArgumentMatchers.any())).thenReturn("request-json");
        when(repository.findReceipt("request-1", KnowledgeOperations.REVIEW_OWN)).thenReturn(null);

        try (var ignored = VerifiedKnowledgeContext.open(List.of(KnowledgeOperations.REVIEW_OWN), Map.of())) {
            assertThatThrownBy(() -> service.review("knowledge-1",
                    new KnowledgeReviewRequest("request-1", 0L, "APPROVE", null)))
                    .isInstanceOfSatisfying(KnowledgeOperationException.class,
                            failure -> assertThat(failure.code()).isEqualTo("REVIEWER_MUST_DIFFER"));
        }

        verify(repository, never()).recordReview(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reviewerCanReadOnlyPendingOwnCaseWithReviewPermission() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        InterviewTranscriptSource source = mock(InterviewTranscriptSource.class);
        KnowledgeLifecycleService service = new KnowledgeLifecycleService(repository, source);
        when(repository.findCase("knowledge-1", false)).thenReturn(caseRecord());
        when(repository.findRevision("knowledge-1", 1)).thenReturn(revisionRecord());
        when(repository.findSources("knowledge-1", 1)).thenReturn(List.of());

        try (var ignored = VerifiedKnowledgeContext.open(List.of(KnowledgeOperations.REVIEW_OWN), Map.of(), "7")) {
            var detail = service.read("knowledge-1");
            assertThat(detail.state()).isEqualTo("REVIEW_PENDING");
        }
    }

    @Test
    void reviewPermissionAloneCannotReadNonPendingOwnCase() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        InterviewTranscriptSource source = mock(InterviewTranscriptSource.class);
        KnowledgeLifecycleService service = new KnowledgeLifecycleService(repository, source);
        KnowledgeCaseRecord draft = new KnowledgeCaseRecord("knowledge-1", "OWN", "1", null,
                "跟进", "客户没反馈", "带看", List.of(), KnowledgeState.DRAFT, 1, null,
                "UNKNOWN", 0, OffsetDateTime.now(), OffsetDateTime.now());
        when(repository.findCase("knowledge-1", false)).thenReturn(draft);

        try (var ignored = VerifiedKnowledgeContext.open(List.of(KnowledgeOperations.REVIEW_OWN), Map.of(), "7")) {
            assertThatThrownBy(() -> service.read("knowledge-1")).isInstanceOf(RuntimeException.class);
        }

        verify(repository, never()).findRevision("knowledge-1", 1);
    }

    @Test
    void publishRequiresAnApprovalForTheCurrentRevision() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        InterviewTranscriptSource source = mock(InterviewTranscriptSource.class);
        KnowledgeLifecycleService service = new KnowledgeLifecycleService(repository, source);
        when(repository.findCase("knowledge-1", true)).thenReturn(caseRecord());
        when(repository.write(org.mockito.ArgumentMatchers.any())).thenReturn("request-json");
        when(repository.findReceipt("request-1", KnowledgeOperations.PUBLISH_OWN)).thenReturn(null);
        when(repository.hasApproval("knowledge-1", 1)).thenReturn(false);

        try (var ignored = VerifiedKnowledgeContext.open(List.of(KnowledgeOperations.PUBLISH_OWN), Map.of())) {
            assertThatThrownBy(() -> service.publish("knowledge-1", new KnowledgeOperationRequest("request-1", 0L)))
                    .isInstanceOfSatisfying(KnowledgeOperationException.class,
                            failure -> assertThat(failure.code()).isEqualTo("REVIEW_REQUIRED"));
        }

        verify(repository, never()).transition(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static KnowledgeCaseRecord caseRecord() {
        return new KnowledgeCaseRecord("knowledge-1", "OWN", "1", null, "跟进", "客户没反馈", "带看",
                List.of(), KnowledgeState.REVIEW_PENDING, 1, null, "UNKNOWN", 0,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static KnowledgeRevisionRecord revisionRecord() {
        return new KnowledgeRevisionRecord(1, "knowledge-1", 1, "hash", "summary",
                new KnowledgeBody("客户没反馈", "带看", List.of(), List.of(), List.of(), List.of(), List.of(), "UNVERIFIED"),
                "UNKNOWN", "UNVERIFIED", "input-hash", "rules-v1", "1", OffsetDateTime.now());
    }
}
