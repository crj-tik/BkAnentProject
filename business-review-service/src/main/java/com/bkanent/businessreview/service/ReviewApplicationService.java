package com.bkanent.businessreview.service;

import com.bkanent.businessreview.domain.*;
import com.bkanent.businessreview.domain.ReviewDomain.*;
import com.bkanent.businessreview.persistence.JdbcReviewRepository.ReviewConflictException;
import com.bkanent.businessreview.persistence.JdbcReviewRepository.ReviewNotFoundException;
import com.bkanent.businessreview.persistence.ReviewRepository;
import com.bkanent.businessreview.security.TrustedStoreScopePort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReviewApplicationService {
    public static final String CREATE = "business.review.create.store";
    public static final String READ = "business.review.read.store";
    public static final String PLAN_WRITE = "business.review.plan.write.store";
    public static final String PLAN_CONFIRM = "business.review.plan.confirm.store";
    public static final String ACTION_FEEDBACK = "business.review.action.feedback.store";
    public static final String OBSERVATION_READ = "business.review.observation.read.store";

    private final ReviewRepository repository;
    private final ReviewDatasetPort datasets;
    private final TrustedStoreScopePort scopes;
    private final ObservationCalculator calculator;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public ReviewApplicationService(ReviewRepository repository, ReviewDatasetPort datasets,
                                    TrustedStoreScopePort scopes, ObservationCalculator calculator,
                                    com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.repository = repository;
        this.datasets = datasets;
        this.scopes = scopes;
        this.calculator = calculator;
        this.mapper = mapper;
    }

    @Transactional
    public ReviewMatter create(Long requestedStoreId, String datasetId) {
        TrustedStoreScopePort.StoreScope scope = requireStore(requestedStoreId, CREATE);
        ReviewDataset dataset = datasets.read(datasetId, scope.storeId(), CREATE);
        ReviewDiagnosis diagnosis = diagnose(dataset);
        ReviewMatter matter = new ReviewMatter(UUID.randomUUID().toString(), scope.storeId(), scope.actorId(),
                dataset.datasetId(), dataset.effectiveMode(), MatterStatus.OPEN, 1, Instant.now(),
                diagnosis, dataset.facts());
        repository.create(matter, calculator.calculate(matter.reviewId(), dataset));
        return matter;
    }

    public ReviewMatter get(String reviewId) {
        ReviewMatter matter = repository.findMatter(reviewId).orElseThrow(ReviewNotFoundException::new);
        requireStore(matter.storeId(), READ);
        if (matter.status() == MatterStatus.PLAN_PENDING_REVIEW) return maskedPending(matter);
        ReviewDataset snapshot = snapshot(matter);
        if (!datasets.sourcesCurrent(snapshot, READ)) {
            repository.markPendingReview(reviewId);
            return maskedPending(matter);
        }
        return matter;
    }

    public CursorPage<ReviewMatter> list(Long requestedStoreId, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new ReviewConflictException("INVALID_ARGUMENT");
        TrustedStoreScopePort.StoreScope scope = requireStore(requestedStoreId, READ);
        int offset = decodeCursor(cursor, scope.actorId(), scope.storeId());
        List<ReviewMatter> stored = repository.listMatters(scope.storeId(), limit + 1, offset);
        List<ReviewMatter> items = stored.stream().map(matter -> {
            if (matter.status() == MatterStatus.PLAN_PENDING_REVIEW) return maskedPending(matter);
            if (datasets.sourcesCurrent(snapshot(matter), READ)) return matter;
            repository.markPendingReview(matter.reviewId());
            return maskedPending(matter);
        }).toList();
        boolean hasNext = items.size() > limit;
        List<ReviewMatter> page = hasNext ? items.subList(0, limit) : items;
        String next = hasNext ? encodeCursor(offset + limit, scope.actorId(), scope.storeId()) : null;
        return new CursorPage<>(page, next);
    }

    public List<ReviewPlan> listPlans(String reviewId) {
        ReviewMatter matter = repository.findMatter(reviewId).orElseThrow(ReviewNotFoundException::new);
        requireStore(matter.storeId(), READ);
        if (matter.status() != MatterStatus.PLAN_PENDING_REVIEW && !datasets.sourcesCurrent(snapshot(matter), READ))
            repository.markPendingReview(reviewId);
        return repository.listPlans(reviewId).stream().map(plan -> "STALE".equals(plan.status())
                ? new ReviewPlan(plan.reviewId(), plan.version(), plan.diagnosisRevision(), plan.ownerActorId(),
                plan.dueAt(), List.of(), List.of(), plan.parameterHash(), plan.status(), plan.createdAt())
                : plan).toList();
    }

    public List<ReviewAction> listActions(String reviewId) {
        ReviewMatter matter = repository.findMatter(reviewId).orElseThrow(ReviewNotFoundException::new);
        requireStore(matter.storeId(), READ);
        if (matter.status() != MatterStatus.PLAN_PENDING_REVIEW && !datasets.sourcesCurrent(snapshot(matter), READ))
            repository.markPendingReview(reviewId);
        return repository.listActions(reviewId);
    }

    @Transactional
    public ReviewPlan writePlan(String reviewId, long expectedMatterVersion, int expectedDiagnosisRevision, Instant dueAt,
                               List<String> actions) {
        ReviewMatter matter = repository.findMatter(reviewId).orElseThrow(ReviewNotFoundException::new);
        TrustedStoreScopePort.StoreScope scope = requireStore(matter.storeId(), PLAN_WRITE);
        if (matter.status() == MatterStatus.PLAN_PENDING_REVIEW)
            throw new ReviewConflictException("REVIEW_REQUIRES_REFRESH");
        if (matter.version() != expectedMatterVersion) throw new ReviewConflictException("REVIEW_VERSION_CONFLICT");
        if (expectedDiagnosisRevision < 1 || repository.latestDiagnosisRevision(reviewId) != expectedDiagnosisRevision)
            throw new ReviewConflictException("DIAGNOSIS_REVISION_CONFLICT");
        if (dueAt == null || !dueAt.isAfter(Instant.now()) || dueAt.isAfter(Instant.now().plus(90, ChronoUnit.DAYS)))
            throw new ReviewConflictException("INVALID_PLAN_DEADLINE");
        if (actions == null || actions.size() > 12 || actions.stream().anyMatch(value -> value != null && value.length() > 500))
            throw new ReviewConflictException("INVALID_ARGUMENT");
        List<String> boundedActions = actions.stream()
                .map(value -> value == null ? "" : value.trim()).filter(value -> !value.isBlank()).distinct().toList();
        if (boundedActions.isEmpty()) throw new ReviewConflictException("PLAN_ACTIONS_REQUIRED");
        ReviewDataset latest = datasets.read(matter.datasetId(), matter.storeId(), PLAN_WRITE);
        if (!sameSourceRevisions(matter.evidence(), latest.facts()) || !datasets.sourcesCurrent(latest, PLAN_WRITE)) {
            repository.markPendingReview(reviewId);
            throw new ReviewConflictException("SOURCE_VERSION_CONFLICT");
        }
        int version = repository.findLatestPlan(reviewId).map(p -> p.version() + 1).orElse(1);
        List<String> revisions = latest.facts().stream().map(f -> sourceKey(f.source())).sorted().toList();
        String hash = hash(Map.of("reviewId", reviewId, "version", version, "expectedMatterVersion", expectedMatterVersion,
                "expectedDiagnosisRevision", expectedDiagnosisRevision, "diagnosisRevision", expectedDiagnosisRevision,
                "ownerActorId", scope.actorId(),
                "dueAt", dueAt.toString(), "actions", boundedActions, "sourceRevisions", revisions));
        ReviewPlan plan = new ReviewPlan(reviewId, version, expectedDiagnosisRevision, scope.actorId(), dueAt, boundedActions,
                revisions, hash, "PENDING", Instant.now());
        repository.appendPlan(plan, expectedMatterVersion);
        return plan;
    }

    @Transactional
    public ConfirmationReceipt confirm(String reviewId, int version, String requestId, String parameterHash) {
        ReviewMatter matter = repository.findMatter(reviewId).orElseThrow(ReviewNotFoundException::new);
        TrustedStoreScopePort.StoreScope scope = requireStore(matter.storeId(), PLAN_CONFIRM);
        if (version < 1 || requestId == null || !requestId.matches("[A-Za-z0-9._:-]{1,128}")
                || parameterHash == null || !parameterHash.matches("[a-f0-9]{64}"))
            throw new ReviewConflictException("INVALID_ARGUMENT");
        ReviewPlan plan = repository.findPlan(reviewId, version).orElseThrow(ReviewNotFoundException::new);
        if (version != repository.findLatestPlan(reviewId).map(ReviewPlan::version).orElse(-1))
            throw new ReviewConflictException("PLAN_VERSION_NOT_CURRENT");
        if (!plan.parameterHash().equals(parameterHash))
            throw new ReviewConflictException("PLAN_PARAMETER_HASH_MISMATCH");
        if ("PENDING".equals(plan.status())) {
            ReviewDataset latest = datasets.read(matter.datasetId(), matter.storeId(), PLAN_CONFIRM);
            if (!sameSourceRevisions(matter.evidence(), latest.facts()) || !datasets.sourcesCurrent(latest, PLAN_CONFIRM)) {
                repository.markPendingReview(reviewId);
                throw new ReviewConflictException("SOURCE_VERSION_CONFLICT");
            }
        }
        return repository.confirm(plan, requestId, parameterHash, scope.actorId());
    }

    @Transactional
    public ReviewAction feedback(String reviewId, String actionId, ActionStatus status,
                                 EvidenceLevel evidenceLevel, String note, long expectedActionVersion, String requestId) {
        ReviewAction action = repository.findAction(actionId).orElseThrow(ReviewNotFoundException::new);
        if (!action.reviewId().equals(reviewId)) throw new ReviewNotFoundException();
        ReviewMatter matter = repository.findMatter(action.reviewId()).orElseThrow(ReviewNotFoundException::new);
        TrustedStoreScopePort.StoreScope scope = requireStore(matter.storeId(), ACTION_FEEDBACK);
        if (evidenceLevel != EvidenceLevel.USER_REPORTED)
            throw new ReviewConflictException("SOURCE_RECEIPT_REQUIRED");
        if (status == null || expectedActionVersion < 1 || requestId == null
                || !requestId.matches("[A-Za-z0-9._:-]{1,128}") || note != null && note.length() > 2000)
            throw new ReviewConflictException("INVALID_ARGUMENT");
        String payloadHash = hash(Map.of("reviewId", reviewId, "actionId", actionId,
                "expectedActionVersion", expectedActionVersion, "status", status.name(),
                "evidenceLevel", EvidenceLevel.USER_REPORTED.name(), "note", note == null ? "" : note,
                "actorId", scope.actorId()));
        return repository.recordUserFeedback(reviewId, actionId, status, note, scope.actorId(),
                expectedActionVersion, requestId, payloadHash);
    }

    @Transactional
    public ReviewObservation observe(String reviewId) {
        ReviewMatter matter = repository.findMatter(reviewId).orElseThrow(ReviewNotFoundException::new);
        requireStore(matter.storeId(), OBSERVATION_READ);
        if (matter.status() == MatterStatus.PLAN_PENDING_REVIEW
                || !datasets.sourcesCurrent(snapshot(matter), OBSERVATION_READ)) {
            repository.markPendingReview(reviewId);
            throw new ReviewConflictException("SOURCE_VERSION_CONFLICT");
        }
        ReviewDataset current = datasets.read(matter.datasetId(), matter.storeId(), OBSERVATION_READ);
        if (!sameSourceRevisions(matter.evidence(), current.facts()) || !datasets.sourcesCurrent(current, OBSERVATION_READ)) {
            repository.markPendingReview(reviewId);
            throw new ReviewConflictException("SOURCE_VERSION_CONFLICT");
        }
        return repository.saveObservation(calculator.calculate(reviewId, current));
    }

    private TrustedStoreScopePort.StoreScope requireStore(Long requestedStoreId, String operation) {
        TrustedStoreScopePort.StoreScope scope = scopes.requireStore(requestedStoreId, operation);
        if (scope == null || !scope.operations().contains(operation))
            throw new com.bkanent.common.authz.AuthzException(com.bkanent.common.authz.AuthzErrorCode.PERMISSION_DENIED);
        if (requestedStoreId != null && requestedStoreId.longValue() != scope.storeId())
            throw new com.bkanent.common.authz.AuthzException(com.bkanent.common.authz.AuthzErrorCode.PERMISSION_DENIED);
        return scope;
    }

    private ReviewDiagnosis diagnose(ReviewDataset dataset) {
        List<String> facts = dataset.facts().stream().map(f -> f.summary()).toList();
        java.util.ArrayList<String> hypotheses = new java.util.ArrayList<>();
        java.util.ArrayList<String> gaps = new java.util.ArrayList<>();
        java.util.ArrayList<String> actions = new java.util.ArrayList<>();
        boolean missingShowingFeedback = dataset.facts().stream().anyMatch(f -> f.kind() == FactKind.SHOWING
                && (f.feedback() == null || f.feedback().isBlank()));
        if (missingShowingFeedback) {
            gaps.add("带看后的客户反馈缺失，无法确认未推进原因。");
            hypotheses.add("反馈收集缺口可能影响后续判断，需向负责人核实；这不是客户意向结论。");
            actions.add("补录带看后的客户反馈及下一步安排。");
        }
        if (dataset.facts().stream().anyMatch(f -> f.kind() == FactKind.NEGOTIATION
                && !"true".equals(f.attributes().get("priceBasisPresent")))) {
            gaps.add("当前议价记录没有可核验的价格依据。");
            hypotheses.add("议价是否受价格影响尚无证据，需核对来源价格及适用条件。");
            actions.add("核验当前价格来源与议价记录的对应版本。");
        }
        if (dataset.facts().stream().anyMatch(f -> f.kind() == FactKind.LISTING_MAINTENANCE
                && "true".equals(f.attributes().get("maintenanceGap")))) {
            gaps.add("房源维护检查项尚未完成来源核验。");
            actions.add("核对待维护项并记录可复核的房源来源回执。");
        }
        if (dataset.effectiveMode() == DataMode.UNKNOWN)
            gaps.add("至少一项来源性质未知，派生结论标记 UNKNOWN。");
        return new ReviewDiagnosis(facts, hypotheses, gaps, actions);
    }

    private boolean sameSourceRevisions(List<ReviewFact> original, List<ReviewFact> current) {
        return original.stream().map(f -> sourceKey(f.source())).sorted().toList()
                .equals(current.stream().map(f -> sourceKey(f.source())).sorted().toList());
    }
    private ReviewDataset snapshot(ReviewMatter matter) {
        Instant observed = Instant.now();
        return new ReviewDataset(matter.datasetId(), matter.dataMode(), observed,
                observed.minus(30, ChronoUnit.DAYS), observed, java.time.ZoneId.of("Asia/Shanghai"),
                matter.evidence());
    }
    private String sourceKey(SourceReference source) {
        return source.sourceSystem() + ":" + source.sourceId() + "@" + source.revision() + ":"
                + source.sourceStatus() + ":" + source.datasetId() + ":" + source.dataMode() + ":" + source.contentHash();
    }

    private ReviewMatter maskedPending(ReviewMatter matter) {
        long visibleVersion = matter.version() + (matter.status() == MatterStatus.PLAN_PENDING_REVIEW ? 0 : 1);
        return new ReviewMatter(matter.reviewId(), matter.storeId(), matter.actorId(), matter.datasetId(),
                matter.dataMode(), MatterStatus.PLAN_PENDING_REVIEW, visibleVersion, matter.createdAt(),
                new ReviewDiagnosis(List.of(), List.of(),
                        List.of("来源版本已变化或撤回，原始建议正文待重新复核。"), List.of()), List.of());
    }
    private String hash(Object value) {
        try {
            byte[] bytes = mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not compute plan parameter hash", failure);
        }
    }

    private int decodeCursor(String cursor, long actorId, long storeId) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            String decoded = new String(java.util.Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", -1);
            if (parts.length != 3 || Long.parseLong(parts[0]) != actorId || Long.parseLong(parts[1]) != storeId)
                throw new IllegalArgumentException();
            int offset = Integer.parseInt(parts[2]);
            if (offset < 0 || offset > 100_000) throw new IllegalArgumentException();
            return offset;
        } catch (RuntimeException failure) {
            throw new ReviewConflictException("INVALID_CURSOR");
        }
    }

    private String encodeCursor(int offset, long actorId, long storeId) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((actorId + ":" + storeId + ":" + offset).getBytes(StandardCharsets.UTF_8));
    }

    public record CursorPage<T>(List<T> items, String nextCursor) {
        public CursorPage { items = List.copyOf(items); }
    }
}
