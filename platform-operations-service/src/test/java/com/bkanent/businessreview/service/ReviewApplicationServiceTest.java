package com.bkanent.businessreview.service;

import com.bkanent.businessreview.domain.FixtureReviewDatasetAdapter;
import com.bkanent.businessreview.domain.ObservationCalculator;
import com.bkanent.businessreview.domain.ReviewDatasetPort;
import com.bkanent.businessreview.domain.ReviewDomain.*;
import com.bkanent.businessreview.persistence.JdbcReviewRepository;
import com.bkanent.businessreview.persistence.ReviewRepository;
import com.bkanent.businessreview.security.TrustedStoreScopePort;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReviewApplicationServiceTest {
    @Test
    void fixtureCreatesConservativeSimulatedMatterAndExplicitEvidenceGaps() {
        ReviewRepository repository = mock(ReviewRepository.class);
        AtomicReference<ReviewMatter> saved = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).create(any(), any());
        TrustedStoreScopePort scopes = (requestedStoreId, operation) -> {
            if (requestedStoreId == null || requestedStoreId != 23L
                    || !ReviewApplicationService.CREATE.equals(operation)) {
                throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
            }
            return new TrustedStoreScopePort.StoreScope(17L, 23L, Set.of(operation), 4L);
        };
        ReviewApplicationService service = new ReviewApplicationService(repository,
                new FixtureReviewDatasetAdapter(), scopes, new ObservationCalculator(), new ObjectMapper());

        ReviewMatter created = service.create(23L, "store-review-demo-v1");

        assertThat(created.dataMode()).isEqualTo(DataMode.SIMULATED);
        assertThat(created.evidence()).hasSize(4);
        assertThat(created.evidence()).allSatisfy(fact -> {
            assertThat(fact.source().sourceSystem()).isNotBlank();
            assertThat(fact.source().sourceId()).isNotBlank();
            assertThat(fact.source().revision()).isNotBlank();
            assertThat(fact.source().observedAt()).isNotNull();
            assertThat(fact.source().eventTime()).isNotNull();
            assertThat(fact.source().datasetId()).isEqualTo("store-review-demo-v1");
        });
        assertThat(created.diagnosis().evidenceGaps())
                .contains("带看后的客户反馈缺失，无法确认未推进原因。", "当前议价记录没有可核验的价格依据。",
                        "房源维护检查项尚未完成来源核验。");
        assertThat(saved.get()).isEqualTo(created);
        verify(repository).create(any(), any());
    }

    @Test
    void requestStoreIdCannotReplaceTrustedStoreScope() {
        ReviewRepository repository = mock(ReviewRepository.class);
        TrustedStoreScopePort trustedStore = (requestedStoreId, operation) ->
                new TrustedStoreScopePort.StoreScope(17L, 23L, Set.of(ReviewApplicationService.CREATE), 4L);
        ReviewApplicationService service = new ReviewApplicationService(repository,
                new FixtureReviewDatasetAdapter(), trustedStore, new ObservationCalculator(), new ObjectMapper());
        assertThatThrownBy(() -> service.create(99L, "store-review-demo-v1"))
                .isInstanceOf(AuthzException.class);
        org.mockito.Mockito.verifyNoInteractions(repository);

        TrustedStoreScopePort missingOperation = (requestedStoreId, operation) ->
                new TrustedStoreScopePort.StoreScope(17L, 23L, Set.of(ReviewApplicationService.READ), 4L);
        ReviewApplicationService readOnly = new ReviewApplicationService(repository,
                new FixtureReviewDatasetAdapter(), missingOperation, new ObservationCalculator(), new ObjectMapper());
        assertThatThrownBy(() -> readOnly.create(23L, "store-review-demo-v1"))
                .isInstanceOf(AuthzException.class);
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void cursorCannotBeReusedAcrossActorsOrStores() {
        ReviewRepository firstRepository = mock(ReviewRepository.class);
        ReviewMatter first = emptyMatter("review-1", 23L, 17L);
        ReviewMatter second = emptyMatter("review-2", 23L, 17L);
        org.mockito.Mockito.when(firstRepository.listMatters(23L, 2, 0)).thenReturn(List.of(first, second));
        TrustedStoreScopePort firstScope = (requestedStoreId, operation) ->
                new TrustedStoreScopePort.StoreScope(17L, 23L, Set.of(operation), 1L);
        ReviewApplicationService firstService = new ReviewApplicationService(firstRepository,
                new FixtureReviewDatasetAdapter(), firstScope, new ObservationCalculator(), new ObjectMapper());
        String cursor = firstService.list(23L, null, 1).nextCursor();
        assertThat(cursor).isNotBlank();

        ReviewRepository secondRepository = mock(ReviewRepository.class);
        TrustedStoreScopePort otherActorScope = (requestedStoreId, operation) ->
                new TrustedStoreScopePort.StoreScope(18L, 23L, Set.of(operation), 1L);
        ReviewApplicationService otherActorService = new ReviewApplicationService(secondRepository,
                new FixtureReviewDatasetAdapter(), otherActorScope, new ObservationCalculator(), new ObjectMapper());

        assertThatThrownBy(() -> otherActorService.list(23L, cursor, 1))
                .isInstanceOf(com.bkanent.businessreview.persistence.JdbcReviewRepository.ReviewConflictException.class)
                .hasMessage("INVALID_CURSOR");
        org.mockito.Mockito.verifyNoInteractions(secondRepository);
    }

    @Test
    void revokedSourceMasksMatterBodyAndMarksItForReview() {
        ReviewRepository repository = mock(ReviewRepository.class);
        AtomicReference<ReviewMatter> saved = new AtomicReference<>();
        AtomicBoolean sourceCurrent = new AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).create(any(), any());
        org.mockito.Mockito.when(repository.findMatter(any())).thenAnswer(invocation -> Optional.of(saved.get()));
        FixtureReviewDatasetAdapter fixture = new FixtureReviewDatasetAdapter();
        ReviewDatasetPort datasets = new ReviewDatasetPort() {
            @Override public ReviewDataset read(String datasetId, long storeId, String operation) {
                return fixture.read(datasetId, storeId, operation);
            }
            @Override public boolean sourcesCurrent(ReviewDataset dataset, String operation) {
                return sourceCurrent.get();
            }
        };
        TrustedStoreScopePort scopes = (requestedStoreId, operation) ->
                new TrustedStoreScopePort.StoreScope(17L, 23L, Set.of(operation), 4L);
        ReviewApplicationService service = new ReviewApplicationService(repository, datasets, scopes,
                new ObservationCalculator(), new ObjectMapper());
        ReviewMatter created = service.create(23L, "store-review-demo-v1");

        sourceCurrent.set(false);
        ReviewMatter masked = service.get(created.reviewId());

        assertThat(masked.status()).isEqualTo(MatterStatus.PLAN_PENDING_REVIEW);
        assertThat(masked.evidence()).isEmpty();
        assertThat(masked.diagnosis().confirmedFacts()).isEmpty();
        assertThat(masked.diagnosis().hypotheses()).isEmpty();
        assertThat(masked.diagnosis().suggestedActions()).isEmpty();
        assertThat(masked.diagnosis().evidenceGaps()).containsExactly("来源版本已变化或撤回，原始建议正文待重新复核。");
        verify(repository).markPendingReview(created.reviewId());
    }

    @Test
    void simulatedReviewCompletesPersistedPlanFeedbackAndObservationFlow() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:review_flow_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createTables(jdbc);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcReviewRepository repository = new JdbcReviewRepository(jdbc, new ObjectMapper().findAndRegisterModules());
        Set<String> operations = Set.of(ReviewApplicationService.CREATE, ReviewApplicationService.READ,
                ReviewApplicationService.PLAN_WRITE, ReviewApplicationService.PLAN_CONFIRM,
                ReviewApplicationService.ACTION_FEEDBACK, ReviewApplicationService.OBSERVATION_READ);
        Set<String> observedOperations = new HashSet<>();
        TrustedStoreScopePort scopes = (requestedStoreId, operation) -> {
            if (requestedStoreId != null && requestedStoreId != 23L || !operations.contains(operation))
                throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
            observedOperations.add(operation);
            return new TrustedStoreScopePort.StoreScope(17L, 23L, operations, 8L);
        };
        ReviewApplicationService service = new ReviewApplicationService(repository,
                new FixtureReviewDatasetAdapter(), scopes, new ObservationCalculator(),
                new ObjectMapper().findAndRegisterModules());

        ReviewMatter matter = transaction.execute(status -> service.create(23L, "store-review-demo-v1"));
        ReviewPlan stalePlan = transaction.execute(status -> service.writePlan(matter.reviewId(), matter.version(), 1,
                Instant.now().plusSeconds(3 * 24 * 60 * 60L), matter.diagnosis().suggestedActions()));
        ReviewPlan plan = transaction.execute(status -> service.writePlan(matter.reviewId(), matter.version() + 1, 1,
                Instant.now().plusSeconds(4 * 24 * 60 * 60L), matter.diagnosis().suggestedActions()));
        assertThatThrownBy(() -> transaction.execute(status -> service.confirm(matter.reviewId(), stalePlan.version(),
                "confirm-stale", stalePlan.parameterHash())))
                .isInstanceOf(JdbcReviewRepository.ReviewConflictException.class)
                .hasMessage("PLAN_VERSION_NOT_CURRENT");
        ConfirmationReceipt receipt = transaction.execute(status -> service.confirm(matter.reviewId(), plan.version(),
                "confirm-1", plan.parameterHash()));
        ReviewAction action = receipt.actions().get(0);
        assertThatThrownBy(() -> transaction.execute(status -> service.feedback(matter.reviewId(), action.actionId(),
                ActionStatus.DONE, EvidenceLevel.SOURCE_CONFIRMED, "未经来源回执", action.version(), "feedback-source")))
                .isInstanceOf(JdbcReviewRepository.ReviewConflictException.class)
                .hasMessage("SOURCE_RECEIPT_REQUIRED");
        ReviewAction feedback = transaction.execute(status -> service.feedback(matter.reviewId(), action.actionId(),
                ActionStatus.DONE, EvidenceLevel.USER_REPORTED, "已补充核验记录", action.version(), "feedback-1"));
        ReviewObservation observation = transaction.execute(status -> service.observe(matter.reviewId()));

        assertThat(matter.dataMode()).isEqualTo(DataMode.SIMULATED);
        assertThat(plan.diagnosisRevision()).isEqualTo(1);
        assertThat(service.listPlans(matter.reviewId())).extracting(ReviewPlan::parameterHash)
                .containsExactly(plan.parameterHash(), stalePlan.parameterHash());
        assertThat(service.listActions(matter.reviewId())).hasSize(3).contains(feedback);
        assertThat(feedback.status()).isEqualTo(ActionStatus.DONE);
        assertThat(feedback.evidenceLevel()).isEqualTo(EvidenceLevel.USER_REPORTED);
        assertThat(feedback.description()).isNotEqualTo(feedback.feedbackNote());
        assertThat(observation.dataMode()).isEqualTo(DataMode.SIMULATED);
        assertThat(observation.efficiency().status()).isEqualTo(MetricStatus.UNKNOWN);
        assertThat(observation.efficiency().reason()).isEqualTo("FEEDBACK_MISSING");
        assertThat(observation.interpretation()).contains("不证明动作导致业务增长");
        assertThat(observedOperations).containsExactlyInAnyOrderElementsOf(operations);
    }

    private static ReviewMatter emptyMatter(String id, long storeId, long actorId) {
        Instant now = Instant.now();
        return new ReviewMatter(id, storeId, actorId, "store-review-demo-v1", DataMode.SIMULATED,
                MatterStatus.OPEN, 1, now, new ReviewDiagnosis(List.of(), List.of(), List.of(), List.of()), List.of());
    }

    private static void createTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE store_review_matter (review_id VARCHAR(64) PRIMARY KEY, store_id BIGINT NOT NULL, actor_id BIGINT NOT NULL, dataset_id VARCHAR(128) NOT NULL, data_mode VARCHAR(16) NOT NULL, status VARCHAR(32) NOT NULL, version BIGINT NOT NULL, matter_json CLOB NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE store_review_evidence (evidence_id VARCHAR(64) PRIMARY KEY, review_id VARCHAR(64) NOT NULL, source_system VARCHAR(96) NOT NULL, source_id VARCHAR(192) NOT NULL, revision VARCHAR(128) NOT NULL, observed_at TIMESTAMP NOT NULL, event_time TIMESTAMP NULL, data_mode VARCHAR(16) NOT NULL, dataset_id VARCHAR(128) NOT NULL, source_status VARCHAR(32) NOT NULL, evidence_json CLOB NOT NULL)");
        jdbc.execute("CREATE TABLE store_review_diagnosis_revision (review_id VARCHAR(64) NOT NULL, diagnosis_revision INT NOT NULL, diagnosis_json CLOB NOT NULL, PRIMARY KEY (review_id, diagnosis_revision))");
        jdbc.execute("CREATE TABLE store_review_plan_revision (review_id VARCHAR(64) NOT NULL, plan_version INT NOT NULL, diagnosis_revision INT NOT NULL, owner_actor_id BIGINT NOT NULL, due_at TIMESTAMP NOT NULL, parameter_hash CHAR(64) NOT NULL, plan_status VARCHAR(24) NOT NULL, plan_json CLOB NOT NULL, PRIMARY KEY (review_id, plan_version))");
        jdbc.execute("CREATE TABLE store_review_action (action_id VARCHAR(64) PRIMARY KEY, review_id VARCHAR(64) NOT NULL, plan_version INT NOT NULL, action_version BIGINT NOT NULL DEFAULT 1, action_status VARCHAR(24) NOT NULL, evidence_level VARCHAR(24) NULL, feedback_note CLOB NULL, action_json CLOB NOT NULL)");
        jdbc.execute("CREATE TABLE store_review_action_receipt (receipt_id VARCHAR(64) PRIMARY KEY, review_id VARCHAR(64) NOT NULL, request_id VARCHAR(128) NOT NULL, receipt_type VARCHAR(32) NOT NULL, receipt_json CLOB NOT NULL, UNIQUE (review_id, request_id, receipt_type))");
        jdbc.execute("CREATE TABLE store_review_idempotency (scope_key VARCHAR(128) NOT NULL, request_id VARCHAR(128) NOT NULL, payload_hash CHAR(64) NOT NULL, receipt_json CLOB NOT NULL, PRIMARY KEY (scope_key, request_id))");
        jdbc.execute("CREATE TABLE store_review_observation (observation_id VARCHAR(64) PRIMARY KEY, review_id VARCHAR(64) NOT NULL, data_mode VARCHAR(16) NOT NULL, observed_at TIMESTAMP NOT NULL, observation_json CLOB NOT NULL)");
    }
}
