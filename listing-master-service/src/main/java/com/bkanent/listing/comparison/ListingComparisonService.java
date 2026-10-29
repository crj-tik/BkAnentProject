package com.bkanent.listing.comparison;

import com.bkanent.common.a2a.VerifiedDomainAuthorization;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.listing.ListingBusinessResponse;
import com.bkanent.common.listing.ListingComparisonCreateRequest;
import com.bkanent.common.listing.ListingDataMode;
import com.bkanent.common.listing.ListingMoney;
import com.bkanent.common.listing.ListingRef;
import com.bkanent.common.listing.ListingSnapshotBatchReadItem;
import com.bkanent.common.listing.ListingSnapshotBatchReadRequest;
import com.bkanent.common.listing.ListingSnapshotCheckItem;
import com.bkanent.common.listing.ListingSnapshotCheckRequest;
import com.bkanent.common.listing.ListingSourceSnapshot;
import com.bkanent.common.listing.ListingSourceStatus;
import com.bkanent.common.listing.ListingSourceVersionRef;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.model.ListingComparisonFacts;
import com.bkanent.listing.comparison.model.ListingComparisonMetric;
import com.bkanent.listing.comparison.model.ListingComparisonLegacySummaryResponse;
import com.bkanent.listing.comparison.model.ListingComparisonReportResponse;
import com.bkanent.listing.comparison.model.ListingComparisonRow;
import com.bkanent.listing.source.ListingBusinessApiException;
import com.bkanent.listing.source.ListingBusinessSourceService;
import com.bkanent.listing.source.ListingAccessScope;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owner-scoped compare/report lifecycle in the Listing domain. */
@Service
public class ListingComparisonService {
    public static final String RULES_VERSION = "listing-comparison-v1";
    private static final String CONTRACT = ListingBusinessResponse.VERSION;
    private final ListingBusinessSourceService sources;
    private final ListingComparisonReportStore store;

    public ListingComparisonService(ListingBusinessSourceService sources, ListingComparisonReportStore store) {
        this.sources = sources;
        this.store = store;
    }

    public ListingComparisonReportResponse create(ListingComparisonCreateRequest request) {
        validateRequest(request);
        VerifiedDomainAuthorization.requireOperation("compare.report.create");
        String actor = Long.toString(VerifiedDomainAuthorization.requireActorId());
        ListingSnapshotBatchReadRequest readRequest = new ListingSnapshotBatchReadRequest(CONTRACT, request.refs());
        List<ListingSnapshotBatchReadItem> read = sources.batchRead(readRequest).items();
        List<ListingSourceSnapshot> snapshots = requireSnapshots(read, request.refs());
        verifyCurrent(snapshots);

        VerifiedDomainAuthorization.requireOperation("compare.report.create");
        if (!actor.equals(Long.toString(VerifiedDomainAuthorization.requireActorId()))) {
            throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
        }
        String sourceHash = ListingComparisonFingerprint.sourceVersionHash(snapshots);
        String cacheKey = ListingComparisonFingerprint.cacheKey(snapshots, RULES_VERSION, request.includeConclusion());
        ListingComparisonReportResponse candidate = buildReport(UUID.randomUUID().toString().replace("-", ""),
                snapshots, request.includeConclusion());
        return store.createIfAbsent(actor, cacheKey, sourceHash, candidate);
    }

    /** Authorized compatibility path; each old numeric ID is explicitly scoped to legacy-local. */
    public ListingComparisonReportResponse createLegacyLocal(List<Long> listingIds, boolean includeConclusion) {
        List<ListingRef> refs = ListingLegacyReferenceMapper.fromIds(listingIds);
        VerifiedDomainAuthorization.requireOperation("compare.report.create");
        var read = sources.batchRead(new ListingSnapshotBatchReadRequest(CONTRACT,
                refs.stream().map(ref -> new ListingSourceVersionRef(ref, null)).toList())).items();
        List<ListingSourceSnapshot> snapshots = requireSnapshots(read,
                refs.stream().map(ref -> new ListingSourceVersionRef(ref, null)).toList());
        List<ListingSourceVersionRef> versioned = snapshots.stream()
                .map(snapshot -> new ListingSourceVersionRef(snapshot.listingRef(), snapshot.revision())).toList();
        return create(new ListingComparisonCreateRequest(CONTRACT, versioned, includeConclusion));
    }

    public ListingComparisonReportResponse getOwned(String reportId) {
        String actor = requireReportReader();
        ListingComparisonReportResponse report = store.findOwned(reportId, actor);
        verifyCurrent(report.sources());
        requireSameReader(actor);
        return report;
    }

    public Resource loadPdf(String reportId) {
        String actor = requireReportReader();
        ListingComparisonReportResponse report = store.findOwned(reportId, actor);
        verifyCurrent(report.sources());
        requireSameReader(actor);
        return store.loadPdf(reportId, actor);
    }

    /** Returns only the old DTO projection for old Long-ID consumers; no share credentials are exposed. */
    public ListingComparisonLegacySummaryResponse createLegacyDto(List<Long> listingIds) {
        ListingComparisonReportResponse report = createLegacyLocal(listingIds, true);
        return new ListingComparisonLegacySummaryResponse(
                legacyListings(report), report.comparisonTableMarkdown(), report.conclusion());
    }

    public List<ListingDTO> legacyListings(ListingComparisonReportResponse report) {
        return report.sources().stream().map(this::legacyDto).toList();
    }

    private String requireReportReader() {
        VerifiedDomainAuthorization.requireOperation("compare.report.read.own");
        return Long.toString(VerifiedDomainAuthorization.requireActorId());
    }

    private void requireSameReader(String actor) {
        VerifiedDomainAuthorization.requireOperation("compare.report.read.own");
        if (!actor.equals(Long.toString(VerifiedDomainAuthorization.requireActorId()))) {
            throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
        }
    }

    private void verifyCurrent(List<ListingSourceSnapshot> snapshots) {
        List<ListingSourceVersionRef> refs = snapshots.stream()
                .map(snapshot -> new ListingSourceVersionRef(snapshot.listingRef(), snapshot.revision())).toList();
        List<ListingSnapshotCheckItem> checks = sources.check(new ListingSnapshotCheckRequest(CONTRACT, refs)).items();
        if (checks == null || checks.size() != refs.size()) throw ListingComparisonException.sourceUnavailable();
        for (ListingSnapshotCheckItem check : checks) {
            switch (check.status()) {
                case CURRENT -> { }
                case STALE -> throw ListingComparisonException.staleSource();
                case NOT_ACCESSIBLE -> throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
                case UNAVAILABLE -> throw ListingComparisonException.sourceUnavailable();
            }
        }
    }

    private List<ListingSourceSnapshot> requireSnapshots(List<ListingSnapshotBatchReadItem> read,
                                                         List<ListingSourceVersionRef> refs) {
        if (read == null || read.size() != refs.size()) throw ListingComparisonException.sourceUnavailable();
        List<ListingSourceSnapshot> result = new ArrayList<>(read.size());
        for (int i = 0; i < refs.size(); i++) {
            ListingSnapshotBatchReadItem item = read.get(i);
            if (item == null || !refs.get(i).listingRef().equals(item.listingRef())) {
                throw ListingComparisonException.sourceUnavailable();
            }
            if (item.status() == ListingSourceStatus.NOT_ACCESSIBLE) {
                throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
            }
            if (item.status() == ListingSourceStatus.UNAVAILABLE) throw ListingComparisonException.sourceUnavailable();
            if (item.status() != ListingSourceStatus.CURRENT || item.snapshot() == null) {
                throw ListingComparisonException.staleSource();
            }
            result.add(item.snapshot());
        }
        return List.copyOf(result);
    }

    private ListingComparisonReportResponse buildReport(String reportId,
                                                        List<ListingSourceSnapshot> snapshots,
                                                        boolean includeConclusion) {
        List<ListingComparisonRow> rows = snapshots.stream().map(this::toRow).toList();
        List<ListingComparisonMetric> metrics = metrics(snapshots);
        return new ListingComparisonReportResponse(reportId, CONTRACT, RULES_VERSION, Instant.now(),
                dataMode(snapshots), snapshots, rows, metrics, markdown(rows),
                conclusion(snapshots, includeConclusion), "/listings/comparisons/" + reportId + "/pdf");
    }

    private ListingComparisonRow toRow(ListingSourceSnapshot snapshot) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("areaSquareMetres", text(snapshot.facts().areaSquareMetres()));
        values.put("totalPriceCny", snapshot.facts().totalPrice() == null ? "未知"
                : snapshot.facts().totalPrice().amount());
        BigDecimal unitPrice = ListingComparisonFacts.unitPriceYuan(snapshot);
        values.put("unitPriceCnyPerSquareMetre", unitPrice == null ? "未知" : unitPrice.toPlainString());
        for (String key : List.of("layout", "floorLevel", "decoration", "schoolZone", "traffic",
                "verificationStatus", "status")) {
            values.put(key, snapshot.facts().attributes().getOrDefault(key, "未知"));
        }
        return new ListingComparisonRow(snapshot.listingRef(), text(snapshot.facts().title()), values);
    }

    private List<ListingComparisonMetric> metrics(List<ListingSourceSnapshot> snapshots) {
        List<BigDecimal> totals = snapshots.stream().map(item -> amount(item.facts().totalPrice()))
                .filter(java.util.Objects::nonNull).toList();
        List<BigDecimal> unitPrices = snapshots.stream().map(ListingComparisonFacts::unitPriceYuan)
                .filter(java.util.Objects::nonNull).toList();
        return List.of(
                metric("lowestTotalPriceCny", min(totals)),
                metric("highestTotalPriceCny", max(totals)),
                metric("lowestUnitPriceCnyPerSquareMetre", min(unitPrices)),
                metric("highestUnitPriceCnyPerSquareMetre", max(unitPrices)));
    }

    private String markdown(List<ListingComparisonRow> rows) {
        StringBuilder table = new StringBuilder("| 来源 | 房源 | 面积㎡ | 总价CNY | 单价CNY/㎡ | 户型 | 楼层 | 装修 | 学区 | 交通 | 状态 |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (ListingComparisonRow row : rows) {
            table.append("| ").append(escape(row.listingRef().sourceSystem() + "/" + row.listingRef().sourceId()))
                    .append(" | ").append(escape(row.title()));
            for (String key : List.of("areaSquareMetres", "totalPriceCny", "unitPriceCnyPerSquareMetre",
                    "layout", "floorLevel", "decoration", "schoolZone", "traffic", "status")) {
                table.append(" | ").append(escape(row.values().get(key)));
            }
            table.append(" |\n");
        }
        return table.toString();
    }

    private String conclusion(List<ListingSourceSnapshot> snapshots, boolean include) {
        if (!include) return "未请求比较结论。";
        var cheapest = snapshots.stream().filter(item -> amount(item.facts().totalPrice()) != null)
                .min(Comparator.comparing(item -> amount(item.facts().totalPrice()))).orElse(null);
        var largest = snapshots.stream().filter(item -> area(item) != null)
                .max(Comparator.comparing(this::area)).orElse(null);
        if (cheapest == null && largest == null) return "总价和面积均缺少可比数据，暂不能得出排序结论。";
        return "共比较 " + snapshots.size() + " 套房源。总价最低："
                + (cheapest == null ? "未知" : text(cheapest.facts().title())) + "；面积最大："
                + (largest == null ? "未知" : text(largest.facts().title()))
                + "。所有结论均基于报告记录的当前来源版本。";
    }

    private ListingDataMode dataMode(List<ListingSourceSnapshot> snapshots) {
        List<ListingDataMode> modes = snapshots.stream().map(ListingSourceSnapshot::dataMode).distinct().toList();
        if (modes.contains(ListingDataMode.UNKNOWN)) return ListingDataMode.UNKNOWN;
        return modes.size() == 1 ? modes.get(0) : ListingDataMode.MIXED;
    }

    private ListingDTO legacyDto(ListingSourceSnapshot snapshot) {
        if (!"legacy-local".equals(snapshot.listingRef().sourceSystem())) {
            throw ListingComparisonException.invalidRequest();
        }
        long id;
        try { id = Long.parseLong(snapshot.listingRef().sourceId()); }
        catch (NumberFormatException invalid) { throw ListingComparisonException.invalidRequest(); }
        Map<String, String> attributes = snapshot.facts().attributes();
        BigDecimal totalWan = amount(snapshot.facts().totalPrice());
        if (totalWan != null) totalWan = totalWan.divide(new BigDecimal("10000"), 4, RoundingMode.HALF_UP);
        return new ListingDTO(id, snapshot.facts().title(), null, attributes.get("layout"), area(snapshot), totalWan,
                attributes.get("status"), attributes.get("floorLevel"), attributes.get("decoration"),
                attributes.get("schoolZone"), attributes.get("traffic"), attributes.get("verificationStatus"));
    }

    private void validateRequest(ListingComparisonCreateRequest request) {
        if (request == null || !CONTRACT.equals(request.contractVersion()) || request.refs() == null
                || request.refs().size() < 2 || request.refs().size() > 6) {
            throw ListingComparisonException.invalidRequest();
        }
        java.util.Set<ListingRef> unique = new java.util.HashSet<>();
        for (ListingSourceVersionRef ref : request.refs()) {
            if (ref == null || ref.listingRef() == null || !unique.add(ref.listingRef())
                    || ref.expectedRevision() == null || ref.expectedRevision().isBlank()) {
                throw ListingComparisonException.invalidRequest();
            }
        }
    }

    private static BigDecimal amount(ListingMoney money) {
        if (money == null || money.unit() != ListingMoney.Unit.YUAN || !"CNY".equals(money.currency())) return null;
        return new BigDecimal(money.amount());
    }

    private BigDecimal area(ListingSourceSnapshot source) {
        return source.facts().areaSquareMetres() == null ? null : new BigDecimal(source.facts().areaSquareMetres());
    }

    private static ListingComparisonMetric metric(String name, BigDecimal value) {
        return new ListingComparisonMetric(name, value == null ? "未知" : value.stripTrailingZeros().toPlainString());
    }

    private static BigDecimal min(List<BigDecimal> values) {
        return values.stream().min(BigDecimal::compareTo).orElse(null);
    }

    private static BigDecimal max(List<BigDecimal> values) {
        return values.stream().max(BigDecimal::compareTo).orElse(null);
    }

    private static String text(String value) { return value == null || value.isBlank() ? "未知" : value; }
    private static String escape(String value) { return text(value).replace("|", "\\|").replace("\r", " ").replace("\n", " "); }
}
