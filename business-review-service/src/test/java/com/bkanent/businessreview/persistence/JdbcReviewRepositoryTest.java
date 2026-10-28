package com.bkanent.businessreview.persistence;

import com.bkanent.businessreview.domain.ReviewDomain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcReviewRepositoryTest {
    private JdbcTemplate jdbc;
    private JdbcReviewRepository repository;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:review_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repository = new JdbcReviewRepository(jdbc, new ObjectMapper().findAndRegisterModules());
        createTables();
    }

    @Test
    void persistsImmutablePlanFeedbackAndIdempotentConfirmationReceipt() {
        ReviewMatter matter = matter("review-1");
        transaction.executeWithoutResult(status -> repository.create(matter, observation(matter.reviewId())));

        ReviewMatter loaded = repository.findMatter(matter.reviewId()).orElseThrow();
        assertThat(loaded.evidence()).hasSize(1);
        assertThat(loaded.diagnosis().evidenceGaps()).contains("反馈待核实");

        String hash = "a".repeat(64);
        ReviewPlan plan = new ReviewPlan(matter.reviewId(), 1, 1, 17L, Instant.now().plusSeconds(3600),
                List.of("补充带看反馈"), List.of("crm:show-1@r1"), hash, "PENDING", Instant.now());
        transaction.executeWithoutResult(status -> repository.appendPlan(plan, 1));

        ConfirmationReceipt receipt = transaction.execute(status ->
                repository.confirm(plan, "request-1", hash, 17L));
        ConfirmationReceipt retry = transaction.execute(status ->
                repository.confirm(plan, "request-1", hash, 17L));
        assertThat(retry.actions()).extracting(ReviewAction::actionId)
                .containsExactlyElementsOf(receipt.actions().stream().map(ReviewAction::actionId).toList());
        assertThat(repository.listActions(matter.reviewId())).hasSize(1);

        ReviewAction original = receipt.actions().get(0);
        ReviewAction done = transaction.execute(status -> repository.recordUserFeedback(matter.reviewId(), original.actionId(),
                ActionStatus.DONE, "已联系负责人", 17L, original.version(), "feedback-1", "f".repeat(64)));
        assertThat(done.status()).isEqualTo(ActionStatus.DONE);
        assertThat(done.version()).isEqualTo(2);
        assertThat(done.evidenceLevel()).isEqualTo(EvidenceLevel.USER_REPORTED);
        assertThat(done.description()).isEqualTo("补充带看反馈");
        assertThat(done.feedbackNote()).isEqualTo("已联系负责人");
        ReviewAction feedbackRetry = transaction.execute(status -> repository.recordUserFeedback(matter.reviewId(),
                original.actionId(), ActionStatus.DONE, "已联系负责人", 17L, original.version(),
                "feedback-1", "f".repeat(64)));
        assertThat(feedbackRetry).isEqualTo(done);
        assertThatThrownBy(() -> transaction.execute(status -> repository.recordUserFeedback(matter.reviewId(),
                original.actionId(), ActionStatus.DONE, "不同反馈", 17L, original.version(),
                "feedback-1", "a".repeat(64))))
                .isInstanceOf(JdbcReviewRepository.ReviewConflictException.class)
                .hasMessage("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_HASH");
        assertThat(repository.findAction(original.actionId()).orElseThrow().description())
                .isEqualTo("补充带看反馈");
        assertThatThrownBy(() -> transaction.execute(status -> repository.recordUserFeedback(matter.reviewId(),
                original.actionId(), ActionStatus.IN_PROGRESS, "非法回退", 17L, done.version(), "feedback-2", "e".repeat(64))))
                .isInstanceOf(JdbcReviewRepository.ReviewConflictException.class)
                .hasMessage("ILLEGAL_ACTION_TRANSITION");
        assertThatThrownBy(() -> transaction.execute(status -> repository.confirm(plan, "request-1",
                "b".repeat(64), 17L)))
                .isInstanceOf(JdbcReviewRepository.ReviewConflictException.class)
                .hasMessage("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_HASH");
    }

    @Test
    void staleTransitionIsIdempotentAndKeepsConfirmedExecutionFacts() {
        ReviewMatter matter = matter("review-2");
        transaction.executeWithoutResult(status -> repository.create(matter, observation(matter.reviewId())));
        String hash = "c".repeat(64);
        ReviewPlan plan = new ReviewPlan(matter.reviewId(), 1, 1, 17L, Instant.now().plusSeconds(3600),
                List.of("核验房源维护"), List.of("listing:L1@r1"), hash, "PENDING", Instant.now());
        transaction.executeWithoutResult(status -> repository.appendPlan(plan, 1));
        transaction.execute(status -> repository.confirm(plan, "request-2", hash, 17L));
        int actionsBefore = repository.listActions(matter.reviewId()).size();

        transaction.executeWithoutResult(status -> repository.markPendingReview(matter.reviewId()));
        long firstVersion = repository.findMatter(matter.reviewId()).orElseThrow().version();
        transaction.executeWithoutResult(status -> repository.markPendingReview(matter.reviewId()));
        ReviewMatter pending = repository.findMatter(matter.reviewId()).orElseThrow();

        assertThat(pending.status()).isEqualTo(MatterStatus.PLAN_PENDING_REVIEW);
        assertThat(pending.version()).isEqualTo(firstVersion);
        assertThat(repository.listActions(matter.reviewId())).hasSize(actionsBefore);
        assertThat(repository.findPlan(matter.reviewId(), 1).orElseThrow().status()).isEqualTo("CONFIRMED");
    }

    @Test
    void concurrentConfirmationReturnsOneDurableReceiptAndCreatesActionsOnce() throws Exception {
        ReviewMatter matter = matter("review-concurrent");
        transaction.executeWithoutResult(status -> repository.create(matter, observation(matter.reviewId())));
        String hash = "d".repeat(64);
        ReviewPlan plan = new ReviewPlan(matter.reviewId(), 1, 1, 17L, Instant.now().plusSeconds(3600),
                List.of("补充带看反馈"), List.of("crm:show-1@r1"), hash, "PENDING", Instant.now());
        transaction.executeWithoutResult(status -> repository.appendPlan(plan, 1));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ConfirmationReceipt> first = executor.submit(() -> confirmWhenReleased(ready, start, plan, "race-1"));
            Future<ConfirmationReceipt> second = executor.submit(() -> confirmWhenReleased(ready, start, plan, "race-1"));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            ConfirmationReceipt firstReceipt = first.get(10, TimeUnit.SECONDS);
            ConfirmationReceipt secondReceipt = second.get(10, TimeUnit.SECONDS);

            assertThat(secondReceipt).isEqualTo(firstReceipt);
            assertThat(repository.listActions(matter.reviewId())).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private ConfirmationReceipt confirmWhenReleased(CountDownLatch ready, CountDownLatch start,
                                                    ReviewPlan plan, String requestId) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("confirmation race did not start");
        return transaction.execute(status -> repository.confirm(plan, requestId, plan.parameterHash(), 17L));
    }

    private void createTables() {
        jdbc.execute("CREATE TABLE store_review_matter (review_id VARCHAR(64) PRIMARY KEY, store_id BIGINT NOT NULL, actor_id BIGINT NOT NULL, dataset_id VARCHAR(128) NOT NULL, data_mode VARCHAR(16) NOT NULL, status VARCHAR(32) NOT NULL, version BIGINT NOT NULL, matter_json CLOB NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE store_review_evidence (evidence_id VARCHAR(64) PRIMARY KEY, review_id VARCHAR(64) NOT NULL, source_system VARCHAR(96) NOT NULL, source_id VARCHAR(192) NOT NULL, revision VARCHAR(128) NOT NULL, observed_at TIMESTAMP NOT NULL, event_time TIMESTAMP NULL, data_mode VARCHAR(16) NOT NULL, dataset_id VARCHAR(128) NOT NULL, source_status VARCHAR(32) NOT NULL, evidence_json CLOB NOT NULL)");
        jdbc.execute("CREATE TABLE store_review_diagnosis_revision (review_id VARCHAR(64) NOT NULL, diagnosis_revision INT NOT NULL, diagnosis_json CLOB NOT NULL, PRIMARY KEY (review_id, diagnosis_revision))");
        jdbc.execute("CREATE TABLE store_review_plan_revision (review_id VARCHAR(64) NOT NULL, plan_version INT NOT NULL, diagnosis_revision INT NOT NULL, owner_actor_id BIGINT NOT NULL, due_at TIMESTAMP NOT NULL, parameter_hash CHAR(64) NOT NULL, plan_status VARCHAR(24) NOT NULL, plan_json CLOB NOT NULL, PRIMARY KEY (review_id, plan_version))");
        jdbc.execute("CREATE TABLE store_review_action (action_id VARCHAR(64) PRIMARY KEY, review_id VARCHAR(64) NOT NULL, plan_version INT NOT NULL, action_version BIGINT NOT NULL DEFAULT 1, action_status VARCHAR(24) NOT NULL, evidence_level VARCHAR(24) NULL, feedback_note CLOB NULL, action_json CLOB NOT NULL)");
        jdbc.execute("CREATE TABLE store_review_action_receipt (receipt_id VARCHAR(64) PRIMARY KEY, review_id VARCHAR(64) NOT NULL, request_id VARCHAR(128) NOT NULL, receipt_type VARCHAR(32) NOT NULL, receipt_json CLOB NOT NULL, UNIQUE (review_id, request_id, receipt_type))");
        jdbc.execute("CREATE TABLE store_review_idempotency (scope_key VARCHAR(128) NOT NULL, request_id VARCHAR(128) NOT NULL, payload_hash CHAR(64) NOT NULL, receipt_json CLOB NOT NULL, PRIMARY KEY (scope_key, request_id))");
        jdbc.execute("CREATE TABLE store_review_observation (observation_id VARCHAR(64) PRIMARY KEY, review_id VARCHAR(64) NOT NULL, data_mode VARCHAR(16) NOT NULL, observed_at TIMESTAMP NOT NULL, observation_json CLOB NOT NULL)");
    }

    private static ReviewMatter matter(String reviewId) {
        Instant now = Instant.now();
        SourceReference source = new SourceReference("crm", "show-1", "r1", now, now,
                DataMode.SIMULATED, "fixture-v1", "CURRENT", null);
        ReviewFact fact = new ReviewFact(FactKind.SHOWING, source, "已登记一次带看", null, false, Map.of());
        ReviewDiagnosis diagnosis = new ReviewDiagnosis(List.of("已登记一次带看"), List.of(),
                List.of("反馈待核实"), List.of("补充带看反馈"));
        return new ReviewMatter(reviewId, 23L, 17L, "fixture-v1", DataMode.SIMULATED,
                MatterStatus.OPEN, 1, now, diagnosis, List.of(fact));
    }

    private static ReviewObservation observation(String reviewId) {
        Instant now = Instant.now();
        MetricObservation unknown = new MetricObservation("effective-next-step-rate", "v1", MetricStatus.UNKNOWN,
                "FEEDBACK_MISSING", now, "Asia/Shanghai", now.minusSeconds(30), now, "showing", 0, 1,
                null, 0, 0);
        MetricObservation stage = new MetricObservation("business-stage", "v1", MetricStatus.UNKNOWN,
                "NO_SOURCE_CONFIRMED_STAGE_TRANSITION", now, "Asia/Shanghai", now.minusSeconds(30), now,
                "source-confirmed-stage-transition", 0, 0, null, 0, 0);
        return new ReviewObservation(reviewId, DataMode.SIMULATED, unknown, stage, now,
                List.of("SIMULATED"), "观察不代表因果增长。");
    }
}
