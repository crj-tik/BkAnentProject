package com.bkanent.agent.studio;

import com.bkanent.agent.service.SupervisorWorkflowQueryService;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class StudioArtifactsService {
    private final StudioRepository repository;
    private final StudioJson json;
    private final SupervisorWorkflowQueryService queries;
    private final StudioWorkspaceService workspaces;
    private final StudioListingReader listings;
    public StudioArtifactsService(StudioRepository repository, StudioJson json, SupervisorWorkflowQueryService queries,
                                  StudioWorkspaceService workspaces, StudioListingReader listings) {
        this.repository = repository; this.json = json; this.queries = queries; this.workspaces = workspaces; this.listings = listings;
    }
    public void syncLatest(String owner, String workId) {
        var work = repository.work(owner, workId, false);
        var runs = repository.jdbc.queryForList("SELECT * FROM studio_run WHERE work_id=? AND owner_id=? ORDER BY created_at_ms DESC,request_id DESC LIMIT 1", workId, owner);
        if (runs.isEmpty()) return;
        var run = runs.get(0); if (json.number(run.get("artifacts_synced")) == 1) return;
        String taskId = json.text(run.get("run_id")); var state = queries.findWorkflow(taskId, owner); if (state.isEmpty()) return;
        for (var artifact : queries.listArtifacts(taskId, owner)) {
            if (!json.text(work.get("session_id")).equals(artifact.sessionId())) throw StudioException.missing();
            var existing = repository.jdbc.queryForList("SELECT series_id FROM studio_artifact_series WHERE work_id=? AND source_artifact_id=?", workId, artifact.artifactId());
            if (!existing.isEmpty()) continue;
            Map<String, Object> raw = json.read(artifact.contentJson());
            var projected = project(artifact.artifactType(), raw);
            String type = json.text(projected.get("type")); Map<String, Object> content = json.object(projected.get("content"));
            List<Map<String, Object>> sources = List.of();
            var input = json.read(json.text(run.get("input_json")));
            var baseRefs = json.objects(input.get("baseArtifactRefs"));
            if (!baseRefs.isEmpty()) {
                content.put("baseArtifactRefs", baseRefs);
                List<Map<String, Object>> inherited = new ArrayList<>();
                for (var base : baseRefs) {
                    var revision = repository.jdbc.queryForList("SELECT r.sources_json FROM studio_artifact_revision r JOIN studio_artifact_series s ON s.series_id=r.series_id WHERE r.series_id=? AND r.version_no=? AND s.owner_id=? AND s.work_id=?", base.get("seriesId"), base.get("version"), owner, workId);
                    if (revision.isEmpty()) throw StudioException.missing();
                    inherited.addAll(json.objects(json.read(json.text(revision.get(0).get("sources_json"))).get("refs")));
                }
                sources = inherited.stream().distinct().toList();
            }
            if ("listing.candidates".equals(type)) {
                List<Map<String, Object>> ids = json.objects(raw.get("listings"));
                if (ids.size() > 20) ids = ids.subList(0, 20);
                var actual = listings.readCurrent(owner, ids.stream().map(StudioListingReader::reference).toList());
                content = Map.of("items", actual.stream().map(listings::publicListing).toList()); sources = actual.stream().map(listings::source).toList();
            }
            Map<String, Object> finalContent = content; List<Map<String, Object>> finalSources = sources;
            repository.transaction(() -> {
                repository.work(owner, workId, true);
                var duplicate = repository.jdbc.queryForList("SELECT series_id FROM studio_artifact_series WHERE work_id=? AND source_artifact_id=?", workId, artifact.artifactId());
                if (!duplicate.isEmpty()) return null;
                create(owner, workId, taskId, artifact.artifactId(), type, json.text(projected.get("title")), finalContent, finalSources, json.number(run.get("context_revision")), "AGENT"); return null;
            });
        }
        if (Set.of("COMPLETED", "FAILED", "CANCELED", "CANCELLED").contains(state.get().workflowStatus()))
            repository.jdbc.update("UPDATE studio_run SET artifacts_synced=1 WHERE work_id=? AND request_id=?", workId, run.get("request_id"));
    }
    private Map<String, Object> project(String oldType, Map<String, Object> raw) {
        String type = "text.result"; String title = "工作成果"; Map<String, Object> content = new LinkedHashMap<>();
        if (Set.of("copy_draft", "copy_draft_body", "marketing_result", "marketing").contains(oldType) && raw.get("draftText") instanceof String body) {
            type = "marketing.draft"; title = "营销草稿"; content.put("body", body); content.put("platform", json.text(raw.get("platform"))); content.put("headline", raw.get("headline") instanceof String headline ? headline : "内容草稿");
        } else if (Set.of("listing_candidates", "listing_result", "listing_search", "listing").contains(oldType) && raw.get("listings") instanceof List<?>) {
            type = "listing.candidates"; title = "候选房源";
        } else if (Set.of("contract_summary", "contract_review_detail").contains(oldType)) {
            type = "contract.status-check"; title = "合同办理检查"; content.put("summary", raw.getOrDefault("summary", "已保留合同相关结果；当前未提供可验证的条款定位。"));
            content.put("checks", List.of(Map.of("title", "办理状态", "status", json.text(raw.get("contractStatus")), "description", "该结果不等于完整条款审查，请核对合同资料。")));
        } else {
            Object safe = sanitize(raw);
            content.put("body", raw.get("summary") instanceof String summary ? summary : "已保存一份工作结果，可以展开核对其内容。"); content.put("data", safe);
        }
        return Map.of("type", type, "title", title, "content", content);
    }
    public Map<String, Object> list(String owner, String workId, String cursor, int requestedLimit) {
        repository.work(owner, workId, false); syncLatest(owner, workId);
        int limit = Math.max(1, Math.min(100, requestedLimit)); long after = 0; String afterId = "";
        if (cursor != null && !cursor.isBlank()) {
            try { String[] pair = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", 2); after = Long.parseLong(pair[0]); afterId = pair[1]; if (afterId.length() > 64) throw new IllegalArgumentException(); }
            catch (Exception ex) { throw StudioException.invalid("成果游标无效"); }
        }
        var rows = repository.jdbc.queryForList("SELECT * FROM studio_artifact_series WHERE work_id=? AND owner_id=? AND (created_at_ms>? OR (created_at_ms=? AND series_id>?)) ORDER BY created_at_ms,series_id LIMIT " + (limit + 1), workId, owner, after, after, afterId);
        boolean more = rows.size() > limit; if (more) rows = rows.subList(0, limit);
        List<Map<String, Object>> items = rows.stream().map(row -> get(owner, json.text(row.get("series_id")), null)).toList();
        String next = null; if (more && !rows.isEmpty()) { var last = rows.get(rows.size() - 1); next = Base64.getUrlEncoder().withoutPadding().encodeToString((last.get("created_at_ms") + ":" + last.get("series_id")).getBytes(StandardCharsets.UTF_8)); }
        return json.page(items, next);
    }
    public Map<String, Object> get(String owner, String seriesId, Long requestedVersion) {
        var series = series(owner, seriesId, false); var work = repository.work(owner, json.text(series.get("work_id")), false);
        long version = requestedVersion == null ? json.number(series.get("current_version")) : requestedVersion;
        var revisions = repository.jdbc.queryForList("SELECT * FROM studio_artifact_revision WHERE series_id=? AND version_no=?", seriesId, version);
        if (revisions.isEmpty()) throw StudioException.missing(); var revision = revisions.get(0);
        List<Map<String, Object>> sources = json.objects(json.read(json.text(revision.get("sources_json"))).get("refs"));
        boolean staleSource = false;
        List<Map<String, Object>> listingSources = sources.stream().filter(source -> "listing".equals(source.get("type"))).toList();
        if (!listingSources.isEmpty()) {
            var current = listings.readCurrent(owner, listingSources.stream().map(StudioListingReader::reference).toList());
            Map<com.bkanent.common.listing.ListingRef, com.bkanent.common.listing.ListingSourceSnapshot> byRef = current.stream()
                    .collect(java.util.stream.Collectors.toMap(com.bkanent.common.listing.ListingSourceSnapshot::listingRef, value -> value));
            for (var source : listingSources) {
                var snapshot = byRef.get(StudioListingReader.reference(source));
                if (snapshot == null || !snapshot.revision().equals(source.get("sourceRevision"))
                        || !snapshot.contentHash().equals(source.get("contentHash"))) staleSource = true;
            }
        }
        var content = json.read(json.text(revision.get("content_json")));
        List<Map<String, Object>> dependencies = new ArrayList<>(json.objects(content.get("baseArtifactRefs")));
        if (content.get("baseArtifactRef") instanceof Map<?, ?>) dependencies.add(json.object(content.get("baseArtifactRef")));
        boolean staleDependency = false;
        for (var dependency : dependencies) {
            var base = series(owner, json.text(dependency.get("seriesId")), false);
            if (!series.get("work_id").equals(base.get("work_id"))) throw StudioException.missing();
            if (json.number(base.get("current_version")) != json.number(dependency.get("version"))) staleDependency = true;
        }
        boolean stale = staleSource || staleDependency || json.number(revision.get("context_revision")) != json.number(work.get("context_revision"));
        Map<String, Object> result = new LinkedHashMap<>(); result.put("seriesId", seriesId); result.put("revisionId", revision.get("revision_id")); result.put("version", version); result.put("sourceArtifactId", series.get("source_artifact_id")); result.put("workId", series.get("work_id")); result.put("taskId", revision.get("task_id")); result.put("type", series.get("artifact_type")); result.put("schemaVersion", series.get("schema_version")); result.put("title", series.get("title")); result.put("content", json.read(json.text(revision.get("content_json")))); result.put("contextRevision", revision.get("context_revision")); result.put("origin", revision.get("origin")); result.put("sourceRefs", sources); result.put("createdAt", json.instant(json.number(revision.get("created_at_ms"))));
        result.put("freshness", Map.of("status", stale ? "STALE" : sources.isEmpty() ? "UNKNOWN" : "CURRENT", "reasons", stale ? List.of(staleSource ? "引用房源已更新" : staleDependency ? "引用成果已有新版本" : "工作条件已有新版本") : List.of()));
        List<Map<String, Object>> actions = new ArrayList<>();
        if ("listing.candidates".equals(series.get("artifact_type"))) actions.add(Map.of("actionId", "compare:" + seriesId, "kind", "COMPARE_LISTINGS", "label", "比较差异", "availability", "AVAILABLE"));
        if ("marketing.draft".equals(series.get("artifact_type"))) actions.add(Map.of("actionId", "publish-plan:" + seriesId, "kind", "PREPARE_PUBLISH_PLAN", "label", "准备发布计划", "availability", "AVAILABLE", "reason", "仅保存当前正文快照；真实账号、素材版本与渠道需继续对接"));
        result.put("actions", actions); return result;
    }
    public Map<String, Object> save(String owner, String seriesId, Map<String, Object> body) {
        var metadata = series(owner, seriesId, false); String workId = json.text(metadata.get("work_id"));
        return repository.transaction(() -> {
            repository.work(owner, workId, true); var series = series(owner, seriesId, true);
            String requestId = json.required(body.get("requestId"), "请求编号", 128); String hash = json.hash(body);
            var duplicate = workspaces.receipt(owner, workId, requestId, hash);
            if (duplicate != null) return get(owner, seriesId, json.number(duplicate.get("version")));
            long expected = json.number(body.get("expectedVersion"));
            if (expected != json.number(series.get("current_version"))) throw StudioException.conflict("VERSION_CONFLICT", "正文已有新版本，你的修改已保留，请对照后合并");
            if (!"marketing.draft".equals(series.get("artifact_type"))) throw StudioException.invalid("该成果暂不支持直接编辑");
            var previous = repository.jdbc.queryForList("SELECT * FROM studio_artifact_revision WHERE series_id=? AND version_no=?", seriesId, expected).get(0);
            Long derived = body.get("derivedFromVersion") == null ? null : json.number(body.get("derivedFromVersion"));
            Map<String, Object> content = json.read(json.text(previous.get("content_json"))); var provenance = previous;
            if (derived != null) {
                var history = repository.jdbc.queryForList("SELECT * FROM studio_artifact_revision WHERE series_id=? AND version_no=?", seriesId, derived);
                if (history.isEmpty()) throw StudioException.missing(); provenance = history.get(0); content = json.read(json.text(provenance.get("content_json")));
            } else {
                var supplied = json.object(body.get("content"));
                if (!(supplied.get("body") instanceof String text) || text.length() > 100000) throw StudioException.invalid("正文缺失或过长");
                content.put("body", supplied.get("body"));
                if (supplied.get("headline") instanceof String headline && headline.length() <= 500) content.put("headline", headline);
            }
            long next = expected + 1; long now = System.currentTimeMillis();
            String note = body.get("note") instanceof String text ? text.substring(0, Math.min(500, text.length())) : "手工编辑";
            repository.jdbc.update("INSERT INTO studio_artifact_revision(revision_id,series_id,version_no,task_id,context_revision,origin,content_json,sources_json,dependencies_json,note,derived_from_version,created_at_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", json.id(), seriesId, next, provenance.get("task_id"), provenance.get("context_revision"), "USER_EDIT", json.write(content), provenance.get("sources_json"), provenance.get("dependencies_json"), note, derived, now);
            repository.jdbc.update("UPDATE studio_artifact_series SET current_version=?,updated_at_ms=? WHERE series_id=? AND current_version=?", next, now, seriesId, expected);
            repository.touch(workId); workspaces.recordReceipt(owner, workId, requestId, hash, "ARTIFACT_REVISION", "COMPLETED", Map.of("seriesId", seriesId, "version", next));
            return get(owner, seriesId, next);
        });
    }
    public Map<String, Object> revisions(String owner, String seriesId, String cursor, int requestedLimit) {
        get(owner, seriesId, null);
        long before = Long.MAX_VALUE;
        if (cursor != null && !cursor.isBlank()) {
            try { before = Long.parseLong(cursor); if (before <= 0) throw new IllegalArgumentException(); }
            catch (Exception exception) { throw StudioException.invalid("版本游标无效"); }
        }
        int limit = Math.max(1, Math.min(100, requestedLimit));
        var rows = repository.jdbc.queryForList("SELECT revision_id,version_no,origin,note,created_at_ms FROM studio_artifact_revision WHERE series_id=? AND version_no<? ORDER BY version_no DESC LIMIT " + (limit + 1), seriesId, before);
        boolean more = rows.size() > limit; if (more) rows = rows.subList(0, limit);
        String next = more ? json.text(rows.get(rows.size() - 1).get("version_no")) : null;
        return json.page(rows.stream().map(row -> Map.of("revisionId", row.get("revision_id"), "version", row.get("version_no"), "origin", row.get("origin"), "note", row.get("note") == null ? "" : row.get("note"), "createdAt", json.instant(json.number(row.get("created_at_ms"))))).toList(), next);
    }
    Map<String, Object> create(String owner, String workId, String taskId, String sourceId, String type, String title, Map<String, Object> content, List<Map<String, Object>> sources, long contextRevision, String origin) {
        String series = json.id(); String revision = json.id(); long now = System.currentTimeMillis();
        repository.jdbc.update("INSERT INTO studio_artifact_series(series_id,work_id,owner_id,source_artifact_id,artifact_type,schema_version,title,current_version,created_at_ms,updated_at_ms) VALUES (?,?,?,?,?,?,?,?,?,?)", series, workId, owner, sourceId, type, "1.0", title, 1, now, now);
        repository.jdbc.update("INSERT INTO studio_artifact_revision(revision_id,series_id,version_no,task_id,context_revision,origin,content_json,sources_json,dependencies_json,note,created_at_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?)", revision, series, 1, taskId, contextRevision, origin, json.write(content), json.write(Map.of("refs", sources)), json.write(Map.of("contextRevision", contextRevision)), "初始版本", now);
        repository.touch(workId); return Map.of("seriesId", series, "revisionId", revision, "version", 1);
    }
    Map<String, Object> series(String owner, String id, boolean lock) {
        var rows = repository.jdbc.queryForList("SELECT * FROM studio_artifact_series WHERE series_id=? AND owner_id=?" + (lock ? " FOR UPDATE" : ""), id, owner);
        if (rows.isEmpty()) throw StudioException.missing(); return rows.get(0);
    }
    private Object sanitize(Object value) {
        if (value instanceof Map<?, ?> map) { Map<String, Object> safe = new LinkedHashMap<>(); map.forEach((key, item) -> { String name = String.valueOf(key); if (!name.toLowerCase(Locale.ROOT).matches(".*(ticket|token|secret|credential|password|authorization|endpoint|baseurl).*$")) safe.put(name, sanitize(item)); }); return safe; }
        if (value instanceof List<?> list) return list.stream().map(this::sanitize).toList();
        return value;
    }
}
