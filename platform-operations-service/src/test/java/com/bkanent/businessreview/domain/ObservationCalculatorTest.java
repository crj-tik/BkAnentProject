package com.bkanent.businessreview.domain;

import com.bkanent.businessreview.domain.ReviewDomain.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationCalculatorTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-02-01T00:00:00Z");
    private static final Instant OBSERVED = Instant.parse("2026-02-02T00:00:00Z");
    private final ObservationCalculator calculator = new ObservationCalculator();

    @Test
    void deduplicatesSameShowingAndCalculatesOnlyWindowEvents() {
        ReviewFact completed = fact("show-1", "r1", Instant.parse("2026-01-10T00:00:00Z"),
                DataMode.REAL, "customer replied", true, OBSERVED);
        ReviewFact incomplete = fact("show-2", "r1", Instant.parse("2026-01-11T00:00:00Z"),
                DataMode.REAL, "scheduled", true, OBSERVED);

        ReviewObservation result = calculator.calculate("review-1", dataset(DataMode.REAL,
                List.of(completed, completed, incomplete)));

        assertThat(result.dataMode()).isEqualTo(DataMode.REAL);
        assertThat(result.efficiency().status()).isEqualTo(MetricStatus.KNOWN);
        assertThat(result.efficiency().aggregationUnit()).isEqualTo("unique-source-showing-in-window");
        assertThat(result.efficiency().numerator()).isEqualTo(2);
        assertThat(result.efficiency().denominator()).isEqualTo(2);
        assertThat(result.efficiency().value()).isEqualTo(1.0);
    }

    @Test
    void missingFeedbackAndUnknownSourcesRemainUnknown() {
        ReviewFact missing = fact("show-1", "r1", Instant.parse("2026-01-10T00:00:00Z"),
                DataMode.REAL, null, false, OBSERVED);
        ReviewObservation noFeedback = calculator.calculate("review-1", dataset(DataMode.REAL, List.of(missing)));
        assertThat(noFeedback.efficiency().status()).isEqualTo(MetricStatus.UNKNOWN);
        assertThat(noFeedback.efficiency().reason()).isEqualTo("FEEDBACK_MISSING");

        ReviewFact unknown = fact("show-2", "r1", Instant.parse("2026-01-10T00:00:00Z"),
                DataMode.UNKNOWN, "responded", true, OBSERVED);
        ReviewObservation unknownSource = calculator.calculate("review-2", dataset(DataMode.REAL, List.of(unknown)));
        assertThat(unknownSource.dataMode()).isEqualTo(DataMode.UNKNOWN);
        assertThat(unknownSource.efficiency().status()).isEqualTo(MetricStatus.UNKNOWN);
    }

    @Test
    void mixedDataModeIsInheritedAndMissingEventTimeIsUnknown() {
        ReviewFact real = fact("show-real", "r1", Instant.parse("2026-01-10T00:00:00Z"),
                DataMode.REAL, "responded", true, OBSERVED);
        ReviewFact simulated = fact("show-sim", "r1", Instant.parse("2026-01-11T00:00:00Z"),
                DataMode.SIMULATED, "responded", true, OBSERVED);
        ReviewObservation mixed = calculator.calculate("review-1", dataset(DataMode.MIXED, List.of(real, simulated)));
        assertThat(mixed.dataMode()).isEqualTo(DataMode.MIXED);
        assertThat(mixed.efficiency().status()).isEqualTo(MetricStatus.KNOWN);

        ReviewFact noTimestamp = fact("show-no-time", "r1", null, DataMode.REAL, "responded", true, OBSERVED);
        ReviewObservation missingTime = calculator.calculate("review-2", dataset(DataMode.REAL, List.of(noTimestamp)));
        assertThat(missingTime.efficiency().status()).isEqualTo(MetricStatus.UNKNOWN);
        assertThat(missingTime.efficiency().reason()).isEqualTo("EVENT_TIME_MISSING");
    }

    @Test
    void zeroDenominatorAndLateCrossWindowEventsAreExplicit() {
        ReviewFact late = fact("show-late", "r1", Instant.parse("2025-12-31T23:00:00Z"),
                DataMode.SIMULATED, "replied", true, Instant.parse("2026-01-02T00:00:00Z"));
        ReviewFact future = fact("show-future", "r1", Instant.parse("2026-02-02T00:00:00Z"),
                DataMode.SIMULATED, "replied", true, OBSERVED);

        ReviewObservation result = calculator.calculate("review-1", dataset(DataMode.SIMULATED, List.of(late, future)));

        assertThat(result.efficiency().status()).isEqualTo(MetricStatus.UNKNOWN);
        assertThat(result.efficiency().reason()).isEqualTo("NO_ELIGIBLE_OPPORTUNITIES");
        assertThat(result.efficiency().denominator()).isZero();
        assertThat(result.efficiency().lateEventCount()).isEqualTo(1);
        assertThat(result.efficiency().crossWindowEventCount()).isEqualTo(2);
    }

    @Test
    void conflictingRevisionsForOneShowingDoNotDoubleCount() {
        ReviewFact first = fact("show-1", "r1", Instant.parse("2026-01-10T00:00:00Z"),
                DataMode.REAL, "replied", true, OBSERVED);
        ReviewFact second = fact("show-1", "r2", Instant.parse("2026-01-10T00:00:00Z"),
                DataMode.REAL, "replied", true, OBSERVED);

        ReviewObservation result = calculator.calculate("review-1", dataset(DataMode.REAL, List.of(first, second)));

        assertThat(result.efficiency().status()).isEqualTo(MetricStatus.UNKNOWN);
        assertThat(result.efficiency().reason()).isEqualTo("SOURCE_REVISION_CONFLICT");
        assertThat(result.efficiency().denominator()).isEqualTo(1);
    }

    private static ReviewDataset dataset(DataMode mode, List<ReviewFact> facts) {
        return new ReviewDataset("dataset-1", mode, OBSERVED, START, END, ZoneId.of("Asia/Shanghai"), facts);
    }

    private static ReviewFact fact(String id, String revision, Instant eventTime, DataMode mode,
                                   String feedback, boolean nextStep, Instant observedAt) {
        SourceReference source = new SourceReference("crm", id, revision, observedAt, eventTime,
                mode, "dataset-1", "CURRENT", null);
        return new ReviewFact(FactKind.SHOWING, source, "Showing fact " + id, feedback, nextStep, Map.of());
    }
}
