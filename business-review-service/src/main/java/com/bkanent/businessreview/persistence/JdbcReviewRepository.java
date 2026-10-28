package com.bkanent.businessreview.persistence;

import com.bkanent.businessreview.domain.ReviewDomain.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcReviewRepository implements ReviewRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcReviewRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void create(ReviewMatter matter, ReviewObservation observation) {
        jdbc.update("INSERT INTO store_review_matter "
                        + "(review_id, store_id, actor_id, dataset_id, data_mode, status, version, matter_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                matter.reviewId(), matter.storeId(), matter.actorId(), matter.datasetId(),
                matter.dataMode().name(), matter.status().name(), matter.version(), write(matter));
        jdbc.update("INSERT INTO store_review_diagnosis_revision "
                        + "(review_id, diagnosis_revision, diagnosis_json) VALUES (?, ?, ?)",
                matter.reviewId(), 1, write(matter.diagnosis()));
        for (ReviewFact fact : matter.evidence()) {
            SourceReference source = fact.source();
            jdbc.update("INSERT INTO store_review_evidence "
                            + "(evidence_id, review_id, source_system, source_id, revision, observed_at, event_time, "
                            + "data_mode, dataset_id, source_status, evidence_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), matter.reviewId(), source.sourceSystem(), source.sourceId(),
                    source.revision(), utc(source.observedAt()), utc(source.eventTime()), source.dataMode().name(),
                    source.datasetId(), source.sourceStatus(), write(fact));
        }
        saveObservation(observation);
    }

    @Override
    public Optional<ReviewMatter> findMatter(String reviewId) {
        List<ReviewMatter> results = jdbc.query("SELECT matter_json FROM store_review_matter WHERE review_id = ?",
                (rs, row) -> read(rs.getString(1), ReviewMatter.class), reviewId);
        return results.stream().findFirst();
    }

    @Override
    public List<ReviewMatter> listMatters(long storeId, int limit, int offset) {
        return jdbc.query("SELECT matter_json FROM store_review_matter WHERE store_id = ? "
                        + "ORDER BY created_at DESC, review_id DESC LIMIT ? OFFSET ?",
                (rs, row) -> read(rs.getString(1), ReviewMatter.class), storeId, limit, offset);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPendingReview(String reviewId) {
        ReviewMatter matter = findMatter(reviewId).orElseThrow(() -> new ReviewNotFoundException());
        ReviewMatter pending = new ReviewMatter(matter.reviewId(), matter.storeId(), matter.actorId(),
                matter.datasetId(), matter.dataMode(), MatterStatus.PLAN_PENDING_REVIEW,
                matter.version() + 1, matter.createdAt(), matter.diagnosis(), matter.evidence());
        int changed = jdbc.update("UPDATE store_review_matter SET status = ?, version = ?, matter_json = ? "
                        + "WHERE review_id = ? AND status <> ?",
                pending.status().name(), pending.version(), write(pending), reviewId,
                MatterStatus.PLAN_PENDING_REVIEW.name());
        if (changed != 1) return;
        List<ReviewPlan> plans = jdbc.query("SELECT plan_json FROM store_review_plan_revision "
                        + "WHERE review_id = ? AND plan_status = 'PENDING'",
                (rs, row) -> read(rs.getString(1), ReviewPlan.class), reviewId);
        for (ReviewPlan plan : plans) {
            ReviewPlan stale = new ReviewPlan(plan.reviewId(), plan.version(), plan.diagnosisRevision(),
                    plan.ownerActorId(), plan.dueAt(), plan.actions(), plan.sourceRevisions(),
                    plan.parameterHash(), "STALE", plan.createdAt());
            jdbc.update("UPDATE store_review_plan_revision SET plan_status = 'STALE', plan_json = ? "
                            + "WHERE review_id = ? AND plan_version = ?",
                    write(stale), reviewId, plan.version());
        }
    }

    @Override
    public Optional<ReviewPlan> findPlan(String reviewId, int version) {
        List<ReviewPlan> results = jdbc.query("SELECT plan_json FROM store_review_plan_revision "
                        + "WHERE review_id = ? AND plan_version = ?",
                (rs, row) -> read(rs.getString(1), ReviewPlan.class), reviewId, version);
        return results.stream().findFirst();
    }

    @Override
    public Optional<ReviewPlan> findLatestPlan(String reviewId) {
        List<ReviewPlan> results = jdbc.query("SELECT plan_json FROM store_review_plan_revision "
                        + "WHERE review_id = ? ORDER BY plan_version DESC LIMIT 1",
                (rs, row) -> read(rs.getString(1), ReviewPlan.class), reviewId);
        return results.stream().findFirst();
    }

    @Override
    public List<ReviewPlan> listPlans(String reviewId) {
        return jdbc.query("SELECT plan_json FROM store_review_plan_revision WHERE review_id = ? ORDER BY plan_version DESC",
                (rs, row) -> read(rs.getString(1), ReviewPlan.class), reviewId);
    }

    @Override
    public int latestDiagnosisRevision(String reviewId) {
        Integer revision = jdbc.queryForObject("SELECT COALESCE(MAX(diagnosis_revision), 0) "
                + "FROM store_review_diagnosis_revision WHERE review_id = ?", Integer.class, reviewId);
        return revision == null ? 0 : revision;
    }

    @Override
    @Transactional
    public void appendPlan(ReviewPlan plan, long expectedMatterVersion) {
        int updated = jdbc.update("UPDATE store_review_matter SET version = version + 1, status = 'PLANNED' "
                        + "WHERE review_id = ? AND version = ?",
                plan.reviewId(), expectedMatterVersion);
        if (updated != 1) throw new ReviewConflictException("REVIEW_VERSION_CONFLICT");
        jdbc.update("INSERT INTO store_review_plan_revision "
                        + "(review_id, plan_version, diagnosis_revision, owner_actor_id, due_at, parameter_hash, plan_status, plan_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                plan.reviewId(), plan.version(), plan.diagnosisRevision(), plan.ownerActorId(),
                utc(plan.dueAt()), plan.parameterHash(),
                plan.status(), write(plan));
        ReviewMatter matter = findMatter(plan.reviewId()).orElseThrow(ReviewNotFoundException::new);
        ReviewMatter planned = new ReviewMatter(matter.reviewId(), matter.storeId(), matter.actorId(),
                matter.datasetId(), matter.dataMode(), MatterStatus.PLANNED, expectedMatterVersion + 1,
                matter.createdAt(), matter.diagnosis(), matter.evidence());
        jdbc.update("UPDATE store_review_matter SET matter_json = ? WHERE review_id = ?",
                write(planned), plan.reviewId());
    }

    @Override
    @Transactional
    public ConfirmationReceipt confirm(ReviewPlan plan, String requestId, String parameterHash, long actorId) {
        String scopeKey = "confirm:" + plan.reviewId();
        int inserted;
        try {
            inserted = jdbc.update("INSERT INTO store_review_idempotency "
                            + "(scope_key, request_id, payload_hash, receipt_json) VALUES (?, ?, ?, '')",
                    scopeKey, requestId, parameterHash);
        } catch (DuplicateKeyException duplicate) {
            inserted = 0;
        }
        List<Idempotency> prior = jdbc.query("SELECT payload_hash, receipt_json FROM store_review_idempotency "
                        + "WHERE scope_key = ? AND request_id = ?",
                (rs, row) -> new Idempotency(rs.getString(1), rs.getString(2)), scopeKey, requestId);
        if (prior.size() != 1) throw new ReviewConflictException("IDEMPOTENCY_RECEIPT_UNAVAILABLE");
        if (!prior.get(0).payloadHash().equals(parameterHash))
            throw new ReviewConflictException("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_HASH");
        if (inserted == 0) {
            if (prior.get(0).receiptJson().isBlank())
                throw new ReviewConflictException("IDEMPOTENCY_RECEIPT_PENDING");
            return read(prior.get(0).receiptJson(), ConfirmationReceipt.class);
        }
        if (!"PENDING".equals(plan.status())) throw new ReviewConflictException("PLAN_NOT_CONFIRMABLE");
        int confirmed = jdbc.update("UPDATE store_review_plan_revision SET plan_status = 'CONFIRMED' "
                        + "WHERE review_id = ? AND plan_version = ? AND plan_status = 'PENDING' "
                        + "AND parameter_hash = ?",
                plan.reviewId(), plan.version(), plan.parameterHash());
        if (confirmed != 1) throw new ReviewConflictException("PLAN_VERSION_NOT_CURRENT");
        ReviewPlan confirmedPlan = new ReviewPlan(plan.reviewId(), plan.version(), plan.diagnosisRevision(),
                plan.ownerActorId(), plan.dueAt(), plan.actions(), plan.sourceRevisions(),
                plan.parameterHash(), "CONFIRMED", plan.createdAt());
        jdbc.update("UPDATE store_review_plan_revision SET plan_json = ? WHERE review_id = ? AND plan_version = ?",
                write(confirmedPlan), plan.reviewId(), plan.version());
        Instant now = Instant.now();
        List<ReviewAction> actions = new ArrayList<>();
        for (String description : plan.actions()) {
            ReviewAction action = new ReviewAction(UUID.randomUUID().toString(), plan.reviewId(),
                    plan.version(), 1, description, ActionStatus.CONFIRMED, null, actorId, now, null, null);
            jdbc.update("INSERT INTO store_review_action "
                            + "(action_id, review_id, plan_version, action_version, action_status, evidence_level, feedback_note, action_json) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    action.actionId(), action.reviewId(), action.planVersion(), action.version(), action.status().name(),
                    null, null, write(action));
            actions.add(action);
        }
        ConfirmationReceipt receipt = new ConfirmationReceipt(requestId, plan.reviewId(), plan.version(),
                parameterHash, actions, now);
        jdbc.update("UPDATE store_review_idempotency SET receipt_json = ? WHERE scope_key = ? AND request_id = ?",
                write(receipt), scopeKey, requestId);
        ReviewMatter matter = findMatter(plan.reviewId()).orElseThrow(ReviewNotFoundException::new);
        ReviewMatter active = new ReviewMatter(matter.reviewId(), matter.storeId(), matter.actorId(),
                matter.datasetId(), matter.dataMode(), MatterStatus.IN_PROGRESS, matter.version(),
                matter.createdAt(), matter.diagnosis(), matter.evidence());
        jdbc.update("UPDATE store_review_matter SET status = 'IN_PROGRESS', matter_json = ? WHERE review_id = ?",
                write(active), plan.reviewId());
        jdbc.update("INSERT INTO store_review_action_receipt "
                        + "(receipt_id, review_id, request_id, receipt_type, receipt_json) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), plan.reviewId(), requestId, "PLAN_CONFIRMED", write(receipt));
        return receipt;
    }

    @Override
    public Optional<ReviewAction> findAction(String actionId) {
        List<ReviewAction> results = jdbc.query("SELECT action_json FROM store_review_action WHERE action_id = ?",
                (rs, row) -> read(rs.getString(1), ReviewAction.class), actionId);
        return results.stream().findFirst();
    }

    @Override
    @Transactional
    public ReviewAction recordUserFeedback(String reviewId, String actionId, ActionStatus nextStatus, String note,
                                           long actorId, long expectedActionVersion, String requestId, String payloadHash) {
        String scopeKey = "feedback:" + reviewId;
        int inserted;
        try {
            inserted = jdbc.update("INSERT INTO store_review_idempotency "
                            + "(scope_key, request_id, payload_hash, receipt_json) VALUES (?, ?, ?, '')",
                    scopeKey, requestId, payloadHash);
        } catch (DuplicateKeyException duplicate) {
            inserted = 0;
        }
        List<Idempotency> prior = jdbc.query("SELECT payload_hash, receipt_json FROM store_review_idempotency "
                        + "WHERE scope_key = ? AND request_id = ?",
                (rs, row) -> new Idempotency(rs.getString(1), rs.getString(2)), scopeKey, requestId);
        if (prior.size() != 1) throw new ReviewConflictException("IDEMPOTENCY_RECEIPT_UNAVAILABLE");
        if (!prior.get(0).payloadHash().equals(payloadHash))
            throw new ReviewConflictException("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_HASH");
        if (inserted == 0) {
            if (prior.get(0).receiptJson().isBlank()) throw new ReviewConflictException("IDEMPOTENCY_RECEIPT_PENDING");
            return read(prior.get(0).receiptJson(), ReviewAction.class);
        }
        ReviewAction current = findAction(actionId).orElseThrow(ReviewNotFoundException::new);
        if (!current.reviewId().equals(reviewId)) throw new ReviewNotFoundException();
        if (current.version() != expectedActionVersion) throw new ReviewConflictException("ACTION_VERSION_CONFLICT");
        boolean valid = (current.status() == ActionStatus.CONFIRMED || current.status() == ActionStatus.IN_PROGRESS)
                && (nextStatus == ActionStatus.IN_PROGRESS || nextStatus == ActionStatus.DONE
                || nextStatus == ActionStatus.CANCELLED);
        if (!valid) throw new ReviewConflictException("ILLEGAL_ACTION_TRANSITION");
        ReviewAction updated = new ReviewAction(current.actionId(), current.reviewId(), current.planVersion(),
                current.version() + 1,
                current.description(), nextStatus, EvidenceLevel.USER_REPORTED, actorId, Instant.now(), null, note);
        int changed = jdbc.update("UPDATE store_review_action SET action_version = ?, action_status = ?, evidence_level = ?, "
                        + "feedback_note = ?, action_json = ? WHERE action_id = ? AND action_version = ? AND action_status = ?",
                updated.version(),
                updated.status().name(), updated.evidenceLevel().name(), note, write(updated),
                actionId, expectedActionVersion, current.status().name());
        if (changed != 1) throw new ReviewConflictException("ACTION_VERSION_CONFLICT");
        jdbc.update("UPDATE store_review_idempotency SET receipt_json = ? WHERE scope_key = ? AND request_id = ?",
                write(updated), scopeKey, requestId);
        jdbc.update("INSERT INTO store_review_action_receipt "
                        + "(receipt_id, review_id, request_id, receipt_type, receipt_json) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), reviewId, requestId, "ACTION_FEEDBACK", write(updated));
        return updated;
    }

    @Override
    public ReviewObservation saveObservation(ReviewObservation observation) {
        jdbc.update("INSERT INTO store_review_observation "
                        + "(observation_id, review_id, data_mode, observed_at, observation_json) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), observation.reviewId(), observation.dataMode().name(),
                utc(observation.observedAt()), write(observation));
        return observation;
    }

    @Override
    public Optional<ReviewObservation> findLatestObservation(String reviewId) {
        List<ReviewObservation> results = jdbc.query("SELECT observation_json FROM store_review_observation "
                        + "WHERE review_id = ? ORDER BY observed_at DESC, observation_id DESC LIMIT 1",
                (rs, row) -> read(rs.getString(1), ReviewObservation.class), reviewId);
        return results.stream().findFirst();
    }

    @Override
    public List<ReviewAction> listActions(String reviewId) {
        return jdbc.query("SELECT action_json FROM store_review_action WHERE review_id = ? ORDER BY action_id",
                (rs, row) -> read(rs.getString(1), ReviewAction.class), reviewId);
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Could not serialize review data", failure); }
    }
    private LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Could not read persisted review data", failure); }
    }
    private record Idempotency(String payloadHash, String receiptJson) { }

    public static class ReviewNotFoundException extends RuntimeException { }
    public static class ReviewConflictException extends RuntimeException {
        private final String code;
        public ReviewConflictException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
