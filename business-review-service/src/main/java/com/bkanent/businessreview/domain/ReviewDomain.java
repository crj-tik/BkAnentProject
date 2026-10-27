package com.bkanent.businessreview.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

public final class ReviewDomain {
    private ReviewDomain() { }
    public enum DataMode { SIMULATED, REAL, MIXED, UNKNOWN }
    public enum FactKind { SHOWING, FOLLOW_UP, NEGOTIATION, LISTING_MAINTENANCE, KNOWLEDGE }
    public enum MatterStatus { OPEN, PLAN_PENDING_REVIEW, PLANNED, IN_PROGRESS, CLOSED }
    public enum ActionStatus { PROPOSED, CONFIRMED, IN_PROGRESS, DONE, CANCELLED }
    public enum EvidenceLevel { USER_REPORTED, SOURCE_CONFIRMED }
    public enum MetricStatus { KNOWN, UNKNOWN }

    public record SourceReference(String sourceSystem, String sourceId, String revision,
                                  Instant observedAt, Instant eventTime, DataMode dataMode,
                                  String datasetId, String sourceStatus, String contentHash) {
        public SourceReference {
            if (sourceSystem == null || sourceSystem.isBlank() || sourceId == null || sourceId.isBlank()
                    || revision == null || revision.isBlank() || observedAt == null
                    || dataMode == null || datasetId == null || datasetId.isBlank()) {
                throw new IllegalArgumentException("A source reference must retain identity, revision and timestamps");
            }
            sourceStatus = sourceStatus == null ? "CURRENT" : sourceStatus;
        }
    }
    public record ReviewFact(FactKind kind, SourceReference source, String summary,
                             String feedback, boolean nextStepRecorded, Map<String, String> attributes) {
        public ReviewFact {
            if (kind == null || source == null || summary == null || summary.isBlank())
                throw new IllegalArgumentException("Review fact and source are required");
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
    public record ReviewDataset(String datasetId, DataMode dataMode, Instant observedAt,
                                Instant windowStart, Instant windowEnd, ZoneId timeZone,
                                List<ReviewFact> facts) {
        public ReviewDataset {
            if (datasetId == null || datasetId.isBlank() || dataMode == null || observedAt == null
                    || windowStart == null || windowEnd == null || !windowStart.isBefore(windowEnd)
                    || timeZone == null) throw new IllegalArgumentException("Dataset identity, window and timezone are required");
            facts = facts == null ? List.of() : List.copyOf(facts);
        }
        public DataMode effectiveMode() {
            boolean real = facts.stream().anyMatch(f -> f.source().dataMode() == DataMode.REAL);
            boolean simulated = facts.stream().anyMatch(f -> f.source().dataMode() == DataMode.SIMULATED);
            boolean unknown = facts.stream().anyMatch(f -> f.source().dataMode() == DataMode.UNKNOWN);
            boolean mixed = facts.stream().anyMatch(f -> f.source().dataMode() == DataMode.MIXED);
            if (unknown || dataMode == DataMode.UNKNOWN) return DataMode.UNKNOWN;
            if (facts.isEmpty()) return dataMode;
            if (mixed || real && simulated) return DataMode.MIXED;
            if (simulated) return DataMode.SIMULATED;
            return real ? DataMode.REAL : dataMode == DataMode.SIMULATED ? DataMode.SIMULATED : DataMode.UNKNOWN;
        }
    }
    public record MetricObservation(String metricId, String definitionVersion, MetricStatus status,
                                    String reason, Instant observedAt, String timeZone,
                                    Instant windowStart, Instant windowEnd, String aggregationUnit,
                                    long numerator, long denominator, Double value,
                                    int lateEventCount, int crossWindowEventCount) { }
    public record ReviewDiagnosis(List<String> confirmedFacts, List<String> hypotheses,
                                  List<String> evidenceGaps, List<String> suggestedActions) {
        public ReviewDiagnosis {
            confirmedFacts = List.copyOf(confirmedFacts);
            hypotheses = List.copyOf(hypotheses);
            evidenceGaps = List.copyOf(evidenceGaps);
            suggestedActions = List.copyOf(suggestedActions);
        }
    }
    public record ReviewMatter(String reviewId, long storeId, long actorId, String datasetId,
                               DataMode dataMode, MatterStatus status, long version,
                               Instant createdAt, ReviewDiagnosis diagnosis, List<ReviewFact> evidence) {
        public ReviewMatter { evidence = List.copyOf(evidence); }
    }
    public record ReviewPlan(String reviewId, int version, int diagnosisRevision, long ownerActorId, Instant dueAt,
                             List<String> actions, List<String> sourceRevisions,
                             String parameterHash, String status, Instant createdAt) {
        public ReviewPlan { actions = List.copyOf(actions); sourceRevisions = List.copyOf(sourceRevisions); }
    }
    public record ReviewAction(String actionId, String reviewId, int planVersion, long version,
                               String description, ActionStatus status, EvidenceLevel evidenceLevel,
                               long actorId, Instant updatedAt, String sourceReceiptId, String feedbackNote) { }
    public record ConfirmationReceipt(String requestId, String reviewId, int planVersion,
                                      String parameterHash, List<ReviewAction> actions, Instant confirmedAt) {
        public ConfirmationReceipt { actions = List.copyOf(actions); }
    }
    public record ReviewObservation(String reviewId, DataMode dataMode,
                                    MetricObservation efficiency, MetricObservation businessStage,
                                    Instant observedAt, List<String> sourceModes, String interpretation) {
        public ReviewObservation { sourceModes = List.copyOf(sourceModes); }
    }
}
