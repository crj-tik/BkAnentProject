package com.bkanent.businessreview.domain;

import com.bkanent.businessreview.domain.ReviewDomain.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ObservationCalculator {
    public ReviewObservation calculate(String reviewId, ReviewDataset dataset) {
        Instant observedAt = dataset.observedAt();
        Map<String, ReviewFact> uniqueShowings = new LinkedHashMap<>();
        boolean conflictingRevisions = false;
        for (ReviewFact fact : dataset.facts()) {
            if (fact.kind() != FactKind.SHOWING) continue;
            String key = fact.source().sourceSystem() + ":" + fact.source().sourceId();
            ReviewFact previous = uniqueShowings.putIfAbsent(key, fact);
            if (previous != null && !previous.source().revision().equals(fact.source().revision()))
                conflictingRevisions = true;
        }

        int late = (int) dataset.facts().stream()
                .filter(f -> f.source().eventTime() != null && f.source().eventTime().isBefore(dataset.windowStart())
                        && !f.source().observedAt().isBefore(dataset.windowStart())).count();
        int crossWindow = (int) dataset.facts().stream()
                .filter(f -> f.source().eventTime() != null && (f.source().eventTime().isBefore(dataset.windowStart())
                        || f.source().eventTime().isAfter(dataset.windowEnd()))).count();

        var showings = uniqueShowings.values().stream().toList();
        boolean missingEventTime = showings.stream().anyMatch(f -> f.source().eventTime() == null);
        var eligible = showings.stream().filter(f -> f.source().eventTime() != null
                && !f.source().eventTime().isBefore(dataset.windowStart())
                && !f.source().eventTime().isAfter(dataset.windowEnd())).toList();
        long denominator = eligible.size();
        long numerator = eligible.stream()
                .filter(f -> f.feedback() != null && !f.feedback().isBlank() && f.nextStepRecorded()).count();
        boolean missingFeedback = eligible.stream().anyMatch(f -> f.feedback() == null || f.feedback().isBlank());
        boolean invalidSourceStatus = dataset.facts().stream().anyMatch(f ->
                !"CURRENT".equals(f.source().sourceStatus()) && !"PUBLISHED".equals(f.source().sourceStatus()));

        MetricObservation efficiency;
        if (dataset.effectiveMode() == DataMode.UNKNOWN || invalidSourceStatus) {
            efficiency = unknown("SOURCE_MODE_OR_STATUS_UNKNOWN", observedAt, dataset, denominator, late, crossWindow);
        } else if (conflictingRevisions) {
            efficiency = unknown("SOURCE_REVISION_CONFLICT", observedAt, dataset, denominator, late, crossWindow);
        } else if (missingEventTime) {
            efficiency = unknown("EVENT_TIME_MISSING", observedAt, dataset, denominator, late, crossWindow);
        } else if (denominator == 0) {
            efficiency = unknown("NO_ELIGIBLE_OPPORTUNITIES", observedAt, dataset, 0, late, crossWindow);
        } else if (missingFeedback) {
            efficiency = unknown("FEEDBACK_MISSING", observedAt, dataset, denominator, late, crossWindow);
        } else {
            efficiency = new MetricObservation("effective-next-step-rate", "store-review-metrics-v1",
                    MetricStatus.KNOWN, null, observedAt, dataset.timeZone().getId(), dataset.windowStart(),
                    dataset.windowEnd(), "unique-source-showing-in-window", numerator, denominator,
                    (double) numerator / denominator, late, crossWindow);
        }

        MetricObservation stage = new MetricObservation("business-stage", "store-review-stage-v1",
                MetricStatus.UNKNOWN, "NO_SOURCE_CONFIRMED_STAGE_TRANSITION", observedAt,
                dataset.timeZone().getId(), dataset.windowStart(), dataset.windowEnd(),
                "source-confirmed-stage-transition", 0, 0, null, late, crossWindow);
        var modes = dataset.facts().stream().map(f -> f.source().dataMode().name()).distinct().sorted().toList();
        return new ReviewObservation(reviewId, dataset.effectiveMode(), efficiency, stage, observedAt, modes,
                "观察值描述该窗口中的记录，不证明动作导致业务增长。");
    }

    private MetricObservation unknown(String reason, Instant observedAt, ReviewDataset dataset,
                                      long denominator, int late, int crossWindow) {
        return new MetricObservation("effective-next-step-rate", "store-review-metrics-v1",
                MetricStatus.UNKNOWN, reason, observedAt, dataset.timeZone().getId(),
                dataset.windowStart(), dataset.windowEnd(), "unique-source-showing-in-window",
                0, denominator, null, late, crossWindow);
    }
}
