package com.bkanent.agent.studio;

import com.bkanent.common.model.ListingComparisonFacts;
import com.bkanent.common.listing.ListingDataMode;
import com.bkanent.common.listing.ListingMoney;
import com.bkanent.common.listing.ListingRef;
import com.bkanent.common.listing.ListingSourceSnapshot;
import com.bkanent.common.listing.ListingSourceVersionRef;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/** Work-local read commands. Side effects must be added through immutable plans and domain approval. */
@Service
public class StudioCommandService {
    private final StudioRepository repository;
    private final StudioJson json;
    private final StudioWorkspaceService works;
    private final StudioArtifactsService artifacts;
    private final StudioListingReader listings;
    public StudioCommandService(StudioRepository repository, StudioJson json, StudioWorkspaceService works,
                                StudioArtifactsService artifacts, StudioListingReader listings) {
        this.repository = repository; this.json = json; this.works = works; this.artifacts = artifacts; this.listings = listings;
    }

    public Map<String, Object> receipt(String owner, String workId, String requestId) {
        repository.work(owner, workId, false);
        var receipt = works.receipt(owner, workId, requestId, null);
        if (receipt == null) throw StudioException.missing();
        // A stored receipt does not extend access to sources after a role or source-state change.
        for (var ref : json.objects(receipt.get("artifactRefs"))) artifacts.get(owner, json.text(ref.get("seriesId")), json.number(ref.get("version")));
        return receipt;
    }

    public Map<String, Object> execute(String owner, String workId, Map<String, Object> body) {
        String requestId = json.required(body.get("requestId"), "请求编号", 128);
        String hash = json.hash(body);
        return repository.transaction(() -> {
            var work = repository.work(owner, workId, true);
            var duplicate = works.receipt(owner, workId, requestId, hash);
            if (duplicate != null) return receipt(owner, workId, requestId);
            if (json.number(body.get("expectedContextRevision")) != json.number(work.get("context_revision")))
                throw StudioException.conflict("CONTEXT_CONFLICT", "工作条件已变化，请核对后重试");
            String action = json.required(body.get("actionId"), "操作编号", 160);
            if ("load-context-listings".equals(action)) {
                var selectedRefs = json.objects(json.read(json.text(work.get("context_json"))).get("refs")).stream()
                        .filter(ref -> "listing".equals(ref.get("type"))).toList();
                if (selectedRefs.isEmpty() || selectedRefs.size() > 20) throw StudioException.invalid("请先引用 1 至 20 套房源");
                var current = listings.readCurrent(owner, selectedRefs.stream().map(StudioListingReader::reference).toList());
                var ref = artifacts.create(owner, workId, null, null, "listing.candidates", "已选房源", Map.of("items", current.stream().map(listings::publicListing).toList()), current.stream().map(listings::source).toList(), json.number(work.get("context_revision")), "COMMAND");
                works.recordReceipt(owner, workId, requestId, hash, "LOAD_CONTEXT_LISTINGS", "COMPLETED", Map.of("artifactRefs", List.of(ref)));
                return receipt(owner, workId, requestId);
            }
            if (action.startsWith("publish-plan:")) {
                String seriesId = action.substring("publish-plan:".length());
                var series = artifacts.series(owner, seriesId, false);
                if (!workId.equals(series.get("work_id")) || !"marketing.draft".equals(series.get("artifact_type"))) throw StudioException.missing();
                var input = json.object(body.get("input"));
                long version = json.number(input.get("version"));
                var draft = artifacts.get(owner, seriesId, version);
                if (!seriesId.equals(input.get("seriesId")) || !draft.get("revisionId").equals(input.get("revisionId")) || version != json.number(series.get("current_version")))
                    throw StudioException.conflict("VERSION_CONFLICT", "正文已有新版本，请保存并核对后准备计划");
                var content = json.object(draft.get("content"));
                Map<String, Object> plan = new LinkedHashMap<>();
                plan.put("planId", json.id()); plan.put("planVersion", 1); plan.put("status", "INCOMPLETE");
                plan.put("baseArtifactRef", Map.of("seriesId", seriesId, "revisionId", draft.get("revisionId"), "version", version));
                plan.put("body", content.get("body")); plan.put("contentHash", json.hash(content)); plan.put("platform", content.getOrDefault("platform", ""));
                plan.put("targets", List.of()); plan.put("materialRefs", List.of()); plan.put("providerMode", "NOT_CONFIGURED");
                plan.put("blockedReasons", List.of("当前仅保存正文快照，尚未绑定真实账号及素材版本", "尚未配置真实发布渠道，不能执行外部发布"));
                plan.put("createdAt", json.instant(System.currentTimeMillis()));
                var ref = artifacts.create(owner, workId, null, null, "publish.plan", "待完善的发布计划", plan,
                        json.objects(draft.get("sourceRefs")), json.number(work.get("context_revision")), "COMMAND");
                works.recordReceipt(owner, workId, requestId, hash, "PREPARE_PUBLISH_PLAN", "COMPLETED", Map.of("artifactRefs", List.of(ref), "message", "已保存正文快照，发布条件尚不齐全"));
                return receipt(owner, workId, requestId);
            }
            if (!action.startsWith("compare:")) throw StudioException.unavailable("当前操作尚未完成领域对接，请通过能力目录核对可用入口");
            String seriesId = action.substring("compare:".length());
            var series = artifacts.series(owner, seriesId, false);
            if (!workId.equals(series.get("work_id")) || !"listing.candidates".equals(series.get("artifact_type"))) throw StudioException.missing();
            var refs = json.objects(body.get("artifactRefs"));
            var bound = refs.stream().filter(ref -> seriesId.equals(ref.get("seriesId"))).findFirst()
                    .orElseThrow(() -> StudioException.invalid("请选择本次候选成果的具体版本"));
            long version = json.number(bound.get("version"));
            if (version != json.number(series.get("current_version"))) throw StudioException.conflict("VERSION_CONFLICT", "候选成果已有新版本，请核对后重试");
            var source = artifacts.get(owner, seriesId, version);
            if (bound.get("revisionId") != null && !bound.get("revisionId").equals(source.get("revisionId"))) throw StudioException.conflict("VERSION_CONFLICT", "成果版本身份不一致");
            var input = json.object(body.get("input"));
            var candidates = json.objects(json.object(source.get("content")).get("items"));
            var selection = comparisonSelection(input, candidates);
            List<ListingSourceSnapshot> current = listings.readExpected(owner, selection.versions(), selection.contentHashes());
            Map<String, Object> content = comparison(current);
            content.put("baseArtifactRef", Map.of("seriesId", seriesId, "revisionId", source.get("revisionId"), "version", version));
            var ref = artifacts.create(owner, workId, null, null, "listing.comparison", "房源差异比较", content,
                    current.stream().map(listings::source).toList(), json.number(work.get("context_revision")), "COMMAND");
            works.recordReceipt(owner, workId, requestId, hash, "COMPARE_LISTINGS", "COMPLETED", Map.of("artifactRefs", List.of(ref)));
            return receipt(owner, workId, requestId);
        });
    }

    private record ComparisonSelection(List<ListingSourceVersionRef> versions,
                                       Map<ListingRef, String> contentHashes) { }

    private ComparisonSelection comparisonSelection(Map<String, Object> input, List<Map<String, Object>> candidates) {
        List<Map<String, Object>> selected = new ArrayList<>();
        if (input.get("listingRefs") instanceof List<?> refs) {
            if (refs.size() < 2 || refs.size() > 6 || refs.stream().anyMatch(value -> !(value instanceof Map<?, ?>)))
                throw StudioException.invalid("请选择 2 至 6 套不同房源");
            refs.forEach(value -> selected.add(json.object(value)));
        } else {
            if (!(input.get("listingIds") instanceof List<?> ids) || ids.size() < 2 || ids.size() > 6
                    || ids.stream().anyMatch(id -> !(id instanceof String))) {
                throw StudioException.invalid("请选择 2 至 6 套不同房源");
            }
            var suppliedVersions = json.objects(input.get("sourceVersions"));
            for (Object id : ids) {
                List<Map<String, Object>> matching = candidates.stream()
                        .filter(candidate -> id.equals(candidate.get("id"))).toList();
                if (matching.size() != 1) throw StudioException.invalid("旧编号无法唯一定位房源来源，请使用稳定来源引用");
                Map<String, Object> candidate = matching.get(0);
                var version = suppliedVersions.stream().filter(value -> id.equals(value.get("id"))).findFirst()
                        .orElseThrow(() -> StudioException.invalid("缺少房源来源版本"));
                Map<String, Object> migrated = new LinkedHashMap<>(candidate);
                if (version.get("sourceRevision") instanceof String revision) migrated.put("sourceRevision", revision);
                selected.add(migrated);
            }
        }
        List<ListingSourceVersionRef> versions = new ArrayList<>();
        Map<ListingRef, String> hashes = new HashMap<>();
        Set<ListingRef> unique = new HashSet<>();
        for (Map<String, Object> item : selected) {
            ListingRef ref = StudioListingReader.reference(item);
            if (!unique.add(ref)) throw StudioException.invalid("比较房源不能重复");
            Map<String, Object> candidate = candidates.stream()
                    .filter(value -> ref.equals(StudioListingReader.reference(value))).findFirst()
                    .orElseThrow(StudioException::missing);
            String revision = item.get("sourceRevision") instanceof String value ? value : null;
            String contentHash = item.get("contentHash") instanceof String value ? value : null;
            String candidateRevision = candidate.get("sourceRevision") instanceof String value ? value : null;
            String candidateHash = candidate.get("contentHash") instanceof String value ? value : null;
            if (revision == null || contentHash == null || !revision.equals(candidateRevision)
                    || !contentHash.equals(candidateHash)) throw StudioException.conflict(
                    "SOURCE_VERSION_CONFLICT", "候选成果缺少稳定来源版本，请重新加载房源");
            versions.add(new ListingSourceVersionRef(ref, revision));
            hashes.put(ref, contentHash);
        }
        if (versions.size() < 2 || versions.size() > 6) throw StudioException.invalid("请选择 2 至 6 套不同房源");
        return new ComparisonSelection(List.copyOf(versions), Map.copyOf(hashes));
    }

    private Map<String, Object> comparison(List<ListingSourceSnapshot> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", values.stream().map(value -> Map.of(
                "listingId", value.listingRef().sourceId(),
                "sourceKey", sourceKey(value),
                "sourceSystem", value.listingRef().sourceSystem(),
                "sourceId", value.listingRef().sourceId(),
                "sourceRevision", value.revision(),
                "contentHash", value.contentHash(),
                "dataMode", value.dataMode().name(),
                "title", value.facts().title() == null ? "房源" : value.facts().title())).toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        row(rows, values, "totalPrice", "总价（万元）", snapshot -> amount(snapshot, ListingMoney.Unit.YUAN) == null
                ? null : decimal(amount(snapshot, ListingMoney.Unit.YUAN).divide(new BigDecimal("10000"), 4, java.math.RoundingMode.HALF_UP)));
        row(rows, values, "totalPriceCny", "总价（元）", snapshot -> decimal(amount(snapshot, ListingMoney.Unit.YUAN)));
        row(rows, values, "area", "面积（㎡）", snapshot -> snapshot.facts().areaSquareMetres());
        row(rows, values, "unitPrice", "单价（元/㎡）", snapshot -> decimal(ListingComparisonFacts.unitPriceYuan(snapshot)));
        row(rows, values, "layout", "户型", snapshot -> attribute(snapshot, "layout"));
        row(rows, values, "floor", "楼层", snapshot -> attribute(snapshot, "floorLevel"));
        row(rows, values, "decoration", "装修", snapshot -> attribute(snapshot, "decoration"));
        row(rows, values, "schoolZone", "学区记录（需核实）", snapshot -> attribute(snapshot, "schoolZone"));
        row(rows, values, "traffic", "交通", snapshot -> attribute(snapshot, "traffic"));
        row(rows, values, "verificationStatus", "核验状态", snapshot -> attribute(snapshot, "verificationStatus"));
        row(rows, values, "status", "房源状态", snapshot -> attribute(snapshot, "status"));
        result.put("rows", rows); result.put("snapshots", values.stream().map(listings::publicListing).toList());
        result.put("snapshotHash", ListingComparisonFacts.sourceFingerprint(values, false));
        result.put("dataMode", aggregateDataMode(values).name());
        result.put("analysisMode", "已授权房源字段比较，未调用 AI 推断");
        result.put("generatedAt", json.instant(System.currentTimeMillis()));
        return result;
    }

    private void row(List<Map<String, Object>> rows, List<ListingSourceSnapshot> listings, String key, String label,
                     java.util.function.Function<ListingSourceSnapshot, String> read) {
        Map<String, String> cells = new LinkedHashMap<>();
        for (var listing : listings) { String value = read.apply(listing); cells.put(sourceKey(listing), value == null || value.isBlank() ? "未知" : value); }
        rows.add(Map.of("key", key, "label", label, "values", cells));
    }
    private String decimal(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }

    private BigDecimal amount(ListingSourceSnapshot snapshot, ListingMoney.Unit unit) {
        ListingMoney money = snapshot.facts().totalPrice();
        return money == null || money.unit() != unit || !"CNY".equals(money.currency())
                ? null : new BigDecimal(money.amount());
    }

    private String attribute(ListingSourceSnapshot snapshot, String key) {
        return snapshot.facts().attributes().getOrDefault(key, "未知");
    }

    private String sourceKey(ListingSourceSnapshot snapshot) {
        return snapshot.listingRef().sourceSystem() + ":" + snapshot.listingRef().sourceId();
    }

    private ListingDataMode aggregateDataMode(List<ListingSourceSnapshot> snapshots) {
        List<ListingDataMode> modes = snapshots.stream().map(ListingSourceSnapshot::dataMode).distinct().toList();
        if (modes.contains(ListingDataMode.UNKNOWN)) return ListingDataMode.UNKNOWN;
        return modes.size() == 1 ? modes.get(0) : ListingDataMode.MIXED;
    }
}
