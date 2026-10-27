package com.bkanent.businessreview.domain;
import com.bkanent.businessreview.domain.ReviewDomain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "business-review.sources", name = "mode", havingValue = "fixture")
public class FixtureReviewDatasetAdapter implements ReviewDatasetPort {
    private static final String FIXTURE_ID = "store-review-demo-v1";
    @Override
    public ReviewDataset read(String datasetId, long storeId, String authorizationOperation) {
        if (!FIXTURE_ID.equals(datasetId))
            throw new ReviewDependencyException("FIXTURE_NOT_FOUND", "Requested fixture dataset is not registered");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant start = now.minus(30, ChronoUnit.DAYS);
        return new ReviewDataset(FIXTURE_ID, DataMode.SIMULATED, now, start, now, ZoneId.of("Asia/Shanghai"),
                List.of(
                        fact(FactKind.SHOWING, "showing-001", now.minus(4, ChronoUnit.DAYS),
                                "一次带看已记录；当前样例没有客户反馈。", null, false, Map.of("feedbackPresent", "false")),
                        fact(FactKind.FOLLOW_UP, "follow-up-001", now.minus(3, ChronoUnit.DAYS),
                                "已登记一次跟进安排。", "客户反馈待补充", true, Map.of("nextStep", "scheduled")),
                        fact(FactKind.NEGOTIATION, "negotiation-001", now.minus(2, ChronoUnit.DAYS),
                                "样例包含议价事项，但没有可核验的价格依据。", null, false, Map.of("priceBasisPresent", "false")),
                        fact(FactKind.LISTING_MAINTENANCE, "maintenance-001", now.minus(1, ChronoUnit.DAYS),
                                "房源维护待检查项尚未完成核实。", null, false, Map.of("maintenanceGap", "true"))));
    }
    @Override
    public boolean sourcesCurrent(ReviewDataset dataset, String authorizationOperation) {
        return dataset != null && FIXTURE_ID.equals(dataset.datasetId())
                && dataset.facts().stream().allMatch(f -> "CURRENT".equals(f.source().sourceStatus()));
    }
    private static ReviewFact fact(FactKind kind, String id, Instant eventTime, String summary,
                                   String feedback, boolean nextStep, Map<String, String> attributes) {
        Instant observed = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return new ReviewFact(kind, new SourceReference("store-business-fixture", id, "1", observed,
                eventTime, DataMode.SIMULATED, FIXTURE_ID, "CURRENT", null),
                summary, feedback, nextStep, attributes);
    }
}
