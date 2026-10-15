package com.bkanent.agent.studio;

import com.bkanent.common.model.ListingComparisonFacts;
import com.bkanent.common.model.ListingDTO;
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
                var current = selectedRefs.stream().map(ref -> listings.read(owner, json.text(ref.get("id")))).toList();
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
            if (!(input.get("listingIds") instanceof List<?> ids) || ids.size() < 2 || ids.size() > 6 || ids.stream().anyMatch(id -> !(id instanceof String)))
                throw StudioException.invalid("请选择 2 至 6 套不同房源");
            List<String> selected = ids.stream().map(Object::toString).distinct().toList();
            if (selected.size() != ids.size()) throw StudioException.invalid("比较房源不能重复");
            var candidates = json.objects(json.object(source.get("content")).get("items"));
            var versions = json.objects(input.get("sourceVersions"));
            List<ListingDTO> current = new ArrayList<>();
            for (String id : selected) {
                var candidate = candidates.stream().filter(item -> id.equals(item.get("id"))).findFirst().orElseThrow(StudioException::missing);
                var expected = versions.stream().filter(item -> id.equals(item.get("id"))).findFirst()
                        .orElseThrow(() -> StudioException.invalid("缺少房源来源版本"));
                var listing = listings.read(owner, id);
                if (!json.hash(listing).equals(candidate.get("sourceRevision")) || !json.hash(listing).equals(expected.get("sourceRevision")))
                    throw StudioException.conflict("SOURCE_VERSION_CONFLICT", "房源资料已变化，请刷新候选成果后重新比较");
                current.add(listing);
            }
            Map<String, Object> content = comparison(current);
            content.put("baseArtifactRef", Map.of("seriesId", seriesId, "revisionId", source.get("revisionId"), "version", version));
            var ref = artifacts.create(owner, workId, null, null, "listing.comparison", "房源差异比较", content,
                    current.stream().map(listings::source).toList(), json.number(work.get("context_revision")), "COMMAND");
            works.recordReceipt(owner, workId, requestId, hash, "COMPARE_LISTINGS", "COMPLETED", Map.of("artifactRefs", List.of(ref)));
            return receipt(owner, workId, requestId);
        });
    }

    private Map<String, Object> comparison(List<ListingDTO> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", values.stream().map(value -> Map.of("listingId", value.id().toString(), "title", value.title() == null ? "房源" : value.title())).toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        row(rows, values, "totalPrice", "总价（万元）", value -> decimal(value.totalPrice()));
        row(rows, values, "area", "面积（㎡）", value -> decimal(value.area()));
        row(rows, values, "unitPrice", "单价（元/㎡）", value -> decimal(ListingComparisonFacts.unitPriceYuan(value)));
        row(rows, values, "layout", "户型", ListingDTO::layout);
        row(rows, values, "floor", "楼层", ListingDTO::floorLevel);
        row(rows, values, "decoration", "装修", ListingDTO::decoration);
        row(rows, values, "schoolZone", "学区记录（需核实）", ListingDTO::schoolZone);
        row(rows, values, "traffic", "交通", ListingDTO::traffic);
        row(rows, values, "verificationStatus", "核验状态", ListingDTO::verificationStatus);
        row(rows, values, "status", "房源状态", ListingDTO::status);
        result.put("rows", rows); result.put("snapshots", values.stream().map(listings::publicListing).toList());
        result.put("snapshotHash", ListingComparisonFacts.fingerprint(values, false));
        result.put("analysisMode", "已授权房源字段比较，未调用 AI 推断");
        result.put("generatedAt", json.instant(System.currentTimeMillis()));
        return result;
    }
    private void row(List<Map<String, Object>> rows, List<ListingDTO> listings, String key, String label, Function<ListingDTO, String> read) {
        Map<String, String> cells = new LinkedHashMap<>();
        for (var listing : listings) { String value = read.apply(listing); cells.put(listing.id().toString(), value == null || value.isBlank() ? "未提供" : value); }
        rows.add(Map.of("key", key, "label", label, "values", cells));
    }
    private String decimal(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
}
