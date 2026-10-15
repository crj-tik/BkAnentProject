package com.bkanent.agent.studio;

import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.model.distributed.SupervisorWorkflowView;
import com.bkanent.agent.orchestration.OrchestrationStore;
import com.bkanent.agent.service.SupervisorAsyncWorkflowService;
import com.bkanent.agent.service.SupervisorWorkflowQueryService;
import com.bkanent.common.agent.SkillSelection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class StudioWorkspaceService {
    private final StudioRepository repository;
    private final StudioJson json;
    private final SupervisorAsyncWorkflowService workflows;
    private final SupervisorWorkflowQueryService queries;
    private final OrchestrationStore orchestration;
    private final StudioListingReader listings;
    public StudioWorkspaceService(StudioRepository repository, StudioJson json, SupervisorAsyncWorkflowService workflows,
                                  SupervisorWorkflowQueryService queries, OrchestrationStore orchestration, StudioListingReader listings) {
        this.repository = repository; this.json = json; this.workflows = workflows; this.queries = queries; this.orchestration = orchestration; this.listings = listings;
    }

    public Map<String, Object> create(String owner, Map<String, Object> body) {
        String requestId = json.required(body.get("requestId"), "请求编号", 128);
        String goal = json.required(body.get("goal"), "工作目标", 8000);
        String title = body.get("title") instanceof String s && !s.isBlank() ? s.trim() : goal;
        title = title.substring(0, Math.min(100, title.length()));
        Map<String, Object> context = json.context(body.get("context"));
        String hash = json.hash(body); String finalTitle = title;
        try {
            return repository.transaction(() -> {
                var existing = repository.jdbc.queryForList("SELECT work_id,creation_hash FROM studio_work WHERE owner_id=? AND creation_request_id=?", owner, requestId);
                if (!existing.isEmpty()) {
                    if (!hash.equals(existing.get(0).get("creation_hash"))) throw StudioException.conflict("COMMAND_CONFLICT", "同一请求编号不能用于不同工作内容");
                    return basic(repository.work(owner, json.text(existing.get(0).get("work_id")), false));
                }
                String id = json.id(); long now = System.currentTimeMillis();
                repository.jdbc.update("INSERT INTO studio_work(work_id,owner_id,session_id,creation_request_id,creation_hash,title,goal,context_json,ui_json,created_at_ms,updated_at_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        id, owner, json.id(), requestId, hash, finalTitle, goal, json.write(context), "{\"pinnedViews\":[]}", now, now);
                return basic(repository.work(owner, id, false));
            });
        } catch (DuplicateKeyException ex) {
            var existing = repository.jdbc.queryForList("SELECT work_id,creation_hash FROM studio_work WHERE owner_id=? AND creation_request_id=?", owner, requestId);
            if (existing.isEmpty() || !hash.equals(existing.get(0).get("creation_hash"))) throw StudioException.conflict("COMMAND_CONFLICT", "请求内容冲突");
            return basic(repository.work(owner, json.text(existing.get(0).get("work_id")), false));
        }
    }

    public Map<String, Object> recent(String owner, String keyword, String cursor, int requestedLimit) {
        int limit = Math.max(1, Math.min(100, requestedLimit));
        List<Object> params = new ArrayList<>(); params.add(owner);
        StringBuilder sql = new StringBuilder("SELECT * FROM studio_work WHERE owner_id=?");
        if (keyword != null && !keyword.isBlank()) {
            if (keyword.length() > 200) throw StudioException.invalid("搜索内容过长");
            String escaped = keyword.replace("=", "==").replace("%", "=%").replace("_", "=_");
            sql.append(" AND (title LIKE ? ESCAPE '=' OR goal LIKE ? ESCAPE '=')"); params.add("%" + escaped + "%"); params.add("%" + escaped + "%");
        }
        if (cursor != null && !cursor.isBlank()) {
            try {
                if (cursor.length() > 256) throw new IllegalArgumentException();
                String[] pair = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", 2);
                long time = Long.parseLong(pair[0]); if (pair.length != 2 || pair[1].length() > 64) throw new IllegalArgumentException();
                sql.append(" AND (updated_at_ms<? OR (updated_at_ms=? AND work_id<?))"); params.add(time); params.add(time); params.add(pair[1]);
            } catch (Exception ex) { throw StudioException.invalid("分页游标无效，请重新读取工作列表"); }
        }
        sql.append(" ORDER BY updated_at_ms DESC,work_id DESC LIMIT ").append(limit + 1);
        var rows = repository.jdbc.queryForList(sql.toString(), params.toArray());
        boolean more = rows.size() > limit; if (more) rows = rows.subList(0, limit);
        List<Map<String, Object>> items = rows.stream().map(this::summary).toList();
        String next = null;
        if (more && !rows.isEmpty()) { var last = rows.get(rows.size() - 1); next = Base64.getUrlEncoder().withoutPadding().encodeToString((last.get("updated_at_ms") + ":" + last.get("work_id")).getBytes(StandardCharsets.UTF_8)); }
        return json.page(items, next);
    }

    public Map<String, Object> snapshot(String owner, String id) {
        var row = repository.work(owner, id, false);
        String sessionId = json.text(row.get("session_id"));
        // Watermark is read BEFORE state. Replayed overlap is intentional; no newer fact is skipped.
        var events = repository.jdbc.queryForList("SELECT id,event_id FROM agent_event_audit WHERE session_id=? ORDER BY id DESC LIMIT 1", sessionId);
        Map<String, Object> watermark = new LinkedHashMap<>(); watermark.put("sessionId", sessionId); watermark.put("sequence", events.isEmpty() ? 0L : events.get(0).get("id")); watermark.put("eventId", events.isEmpty() ? null : events.get(0).get("event_id"));
        Map<String, Object> result = basic(row); result.put("eventWatermark", watermark);
        if (json.objects(json.read(json.text(row.get("context_json"))).get("refs")).stream().anyMatch(ref -> "listing".equals(ref.get("type"))))
            result.put("allowedActions", List.of(Map.of("actionId", "load-context-listings", "kind", "LOAD_CONTEXT_LISTINGS", "label", "展开所选房源", "availability", "AVAILABLE")));
        var runs = repository.jdbc.queryForList("SELECT * FROM studio_run WHERE work_id=? AND owner_id=? ORDER BY created_at_ms DESC,request_id DESC LIMIT 1", id, owner);
        if (!runs.isEmpty()) {
            var current = runView(owner, row, runs.get(0)); result.put("activeRun", current);
            Optional<SupervisorWorkflowView> state = queries.findWorkflow(json.text(runs.get(0).get("run_id")), owner);
            state.ifPresent(view -> {
                result.put("pendingInput", view.orchestration().get("pendingInput"));
                result.put("pendingDecisions", view.pendingApproval() == null ? List.of() : List.of(view.pendingApproval()));
                if (view.finalAnswer() != null && !view.finalAnswer().isBlank()) {
                    String requestId = json.text(runs.get(0).get("request_id"));
                    var existing = repository.jdbc.queryForList("SELECT message_id FROM studio_message WHERE work_id=? AND request_id=? AND role='assistant'", id, requestId);
                    if (existing.isEmpty()) {
                        try { repository.jdbc.update("INSERT INTO studio_message(message_id,work_id,request_id,task_id,role,content,created_at_ms) VALUES (?,?,?,?,?,?,?)", json.id(), id, requestId, view.taskId(), "assistant", publicText(view.finalAnswer()), System.currentTimeMillis()); }
                        catch (DuplicateKeyException ignored) { /* A concurrent snapshot recorded the same visible reply. */ }
                    } else repository.jdbc.update("UPDATE studio_message SET content=? WHERE work_id=? AND request_id=? AND role='assistant'", publicText(view.finalAnswer()), id, requestId);
                }
            });
        }
        return result;
    }

    public Map<String, Object> context(String owner, String id, Map<String, Object> body) {
        return repository.transaction(() -> {
            var row = repository.work(owner, id, true);
            String requestId = json.required(body.get("requestId"), "请求编号", 128); String hash = json.hash(body);
            Map<String, Object> previous = receipt(owner, id, requestId, hash);
            if (previous != null) return basic(repository.work(owner, id, false));
            long expected = json.number(body.get("expectedContextRevision"));
            if (expected != json.number(row.get("context_revision"))) throw StudioException.conflict("CONTEXT_CONFLICT", "条件已有新版本，请重新核对后保存");
            var changes = json.object(body.get("changes"));
            Map<String, Object> merged = json.read(json.text(row.get("context_json"))); merged.putAll(changes);
            Map<String, Object> next = json.context(merged);
            long now = System.currentTimeMillis();
            repository.jdbc.update("UPDATE studio_work SET context_json=?,context_revision=context_revision+1,revision=revision+1,updated_at_ms=? WHERE work_id=? AND owner_id=? AND context_revision=?", json.write(next), now, id, owner, expected);
            var result = basic(repository.work(owner, id, false));
            recordReceipt(owner, id, requestId, hash, "CONTEXT", "COMPLETED", Map.of("contextRevision", expected + 1));
            return result;
        });
    }

    public Map<String, Object> ui(String owner, String id, Map<String, Object> body) {
        return repository.transaction(() -> {
            var row = repository.work(owner, id, true);
            String requestId = json.required(body.get("requestId"), "请求编号", 128); String hash = json.hash(body);
            var previous = receipt(owner, id, requestId, hash); if (previous != null) return uiView(row);
            long expected = json.number(body.get("expectedUiRevision"));
            if (expected != json.number(row.get("ui_revision"))) throw StudioException.conflict("UI_CONFLICT", "浏览偏好已有新版本，请重新打开后保存");
            Map<String, Object> ui = json.read(json.text(row.get("ui_json")));
            if (body.containsKey("activeSeriesId")) {
                String series = json.required(body.get("activeSeriesId"), "成果编号", 64);
                Integer count = repository.jdbc.queryForObject("SELECT COUNT(*) FROM studio_artifact_series WHERE series_id=? AND work_id=? AND owner_id=?", Integer.class, series, id, owner);
                if (count == null || count != 1) throw StudioException.missing(); ui.put("activeSeriesId", series);
            }
            if (body.containsKey("pinnedViews")) {
                if (!(body.get("pinnedViews") instanceof List<?> values) || values.size() > 20 || values.stream().anyMatch(value -> !(value instanceof String s) || s.length() > 80)) throw StudioException.invalid("固定视图格式无效");
                ui.put("pinnedViews", values);
            }
            repository.jdbc.update("UPDATE studio_work SET ui_json=?,ui_revision=ui_revision+1 WHERE work_id=? AND owner_id=? AND ui_revision=?", json.write(ui), id, owner, expected);
            var result = uiView(repository.work(owner, id, false)); recordReceipt(owner, id, requestId, hash, "UI_STATE", "COMPLETED", result); return result;
        });
    }

    public Map<String, Object> submit(String owner, String id, Map<String, Object> body) {
        return repository.transaction(() -> {
            var work = repository.work(owner, id, true);
            String requestId = json.required(body.get("requestId"), "请求编号", 128); String hash = json.hash(body);
            var existing = receipt(owner, id, requestId, hash); if (existing != null) return json.object(existing.get("run"));
            if (json.number(body.get("expectedContextRevision")) != json.number(work.get("context_revision"))) throw StudioException.conflict("CONTEXT_CONFLICT", "工作条件已变化，请核对后重新执行");
            String message = json.required(body.get("message"), "工作要求", 12000);
            String mode = json.required(body.get("mode"), "执行方式", 16);
            String continuation = null;
            Map<String, Object> context = new LinkedHashMap<>();
            SkillSelection skill = null; Boolean allowMcp = null;
            long effectiveContextRevision = json.number(work.get("context_revision"));
            if ("CONTINUE".equals(mode)) {
                continuation = json.required(body.get("continueRunId"), "续接执行编号", 128);
                var associations = repository.jdbc.queryForList("SELECT run_id FROM studio_run WHERE work_id=? AND owner_id=? AND run_id=?", id, owner, continuation);
                if (associations.isEmpty()) throw StudioException.missing();
                var state = queries.findWorkflow(continuation, owner).orElseThrow(StudioException::missing);
                if (!"WAITING_USER_INPUT".equals(state.workflowStatus())) throw StudioException.conflict("CONTINUATION_STATE_INVALID", "当前执行不在等待补充状态");
                var original = orchestration.originalRequest(continuation); context.putAll(original.context()); skill = original.skill(); allowMcp = original.allowMcp();
                effectiveContextRevision = json.number(context.get("contextRevision"));
            } else if ("NEW".equals(mode)) {
                var previous = repository.jdbc.queryForList("SELECT * FROM studio_run WHERE work_id=? ORDER BY created_at_ms DESC,request_id DESC LIMIT 1", id);
                if (!previous.isEmpty()) {
                    String status = json.text(runView(owner, work, previous.get(0)).get("rawStatus"));
                    if (Set.of("ACCEPTED", "RUNNING", "CANCEL_REQUESTED", "WAITING_USER_APPROVAL", "WAITING_USER_INPUT").contains(status)) throw StudioException.conflict("RUN_ACTIVE", "请先完成当前执行、补充或待决策事项");
                }
                context.put("workContext", json.read(json.text(work.get("context_json")))); context.put("workId", id); context.put("contextRevision", work.get("context_revision"));
                // New Studio tasks use the existing approval gate until per-capability write policies are available.
                context.put("requireApproval", true);
                List<Map<String, Object>> refs = json.objects(body.get("baseArtifactRefs"));
                if (refs.size() > 20) throw StudioException.invalid("引用成果过多");
                for (var ref : refs) {
                    String series = json.required(ref.get("seriesId"), "成果引用", 64); long version = json.number(ref.get("version"));
                    var found = repository.jdbc.queryForList("SELECT r.*,s.artifact_type,s.title FROM studio_artifact_series s JOIN studio_artifact_revision r ON r.series_id=s.series_id WHERE s.series_id=? AND s.work_id=? AND s.owner_id=? AND r.version_no=?", series, id, owner, version);
                    if (found.isEmpty()) throw StudioException.missing(); var saved = found.get(0);
                    if (ref.get("revisionId") != null && !ref.get("revisionId").equals(saved.get("revision_id"))) throw StudioException.conflict("VERSION_CONFLICT", "引用成果的版本身份不一致");
                    var sources = json.objects(json.read(json.text(saved.get("sources_json"))).get("refs"));
                    for (var source : sources) if ("listing".equals(source.get("type"))) listings.read(owner, json.text(source.get("id")));
                    @SuppressWarnings("unchecked") List<Map<String, Object>> snapshots = (List<Map<String, Object>>) context.computeIfAbsent("artifactSnapshots", key -> new ArrayList<Map<String, Object>>());
                    snapshots.add(Map.of("seriesId", series, "revisionId", saved.get("revision_id"), "version", version, "type", saved.get("artifact_type"), "content", json.read(json.text(saved.get("content_json"))), "sourceRefs", sources));
                }
                context.put("artifactRefs", refs);
                if (json.write(context).length() > 160000) throw StudioException.invalid("引用成果内容过多，请减少引用后执行");
                if (body.get("skill") instanceof Map<?, ?>) {
                    var selected = json.object(body.get("skill")); skill = new SkillSelection(json.required(selected.get("name"), "技能名称", 200), selected.get("version") instanceof String v ? v : null);
                }
            } else throw StudioException.invalid("不支持的执行方式");
            context.put("userId", owner);
            String executionRequestId = UUID.nameUUIDFromBytes((owner + ":" + id + ":" + requestId).getBytes(StandardCharsets.UTF_8)).toString();
            var request = new SupervisorTaskRequest(json.text(work.get("session_id")), owner, executionRequestId, executionRequestId, message, context, "studio", true, skill, continuation, allowMcp);
            // MyBatis and JdbcTemplate share this transaction: dispatch cannot observe a half-linked submission.
            var accepted = workflows.submitWorkflow(request);
            long now = System.currentTimeMillis();
            var frozenInput = new LinkedHashMap<String, Object>(body); frozenInput.put("contextSnapshot", context); frozenInput.put("effectiveContextRevision", effectiveContextRevision); frozenInput.put("baseArtifactRefs", context.getOrDefault("artifactRefs", List.of()));
            repository.jdbc.update("INSERT INTO studio_run(work_id,request_id,owner_id,run_id,async_workflow_id,context_revision,input_json,created_at_ms) VALUES (?,?,?,?,?,?,?,?)", id, requestId, owner, accepted.taskId(), accepted.asyncWorkflowId(), effectiveContextRevision, json.write(frozenInput), now);
            repository.jdbc.update("INSERT INTO studio_message(message_id,work_id,request_id,task_id,role,content,created_at_ms) VALUES (?,?,?,?,?,?,?)", json.id(), id, requestId, accepted.taskId(), "user", publicText(message), now);
            repository.touch(id);
            Map<String, Object> run = new LinkedHashMap<>(); run.put("runId", accepted.taskId()); run.put("taskId", accepted.taskId()); run.put("asyncWorkflowId", accepted.asyncWorkflowId()); run.put("sessionId", accepted.sessionId()); run.put("rawStatus", accepted.status()); run.put("contextRevision", effectiveContextRevision); run.put("createdAt", json.instant(now));
            recordReceipt(owner, id, requestId, hash, "RUN", "ACCEPTED", Map.of("run", run)); return run;
        });
    }

    public Map<String, Object> runs(String owner, String id, String cursor, int limit) {
        var work = repository.work(owner, id, false);
        return pageRows(id, "studio_run", "request_id", cursor, limit, row -> runView(owner, work, row));
    }
    public Map<String, Object> messages(String owner, String id, String cursor, int limit) {
        repository.work(owner, id, false);
        return pageRows(id, "studio_message", "message_id", cursor, limit, row -> {
            Map<String, Object> result = new LinkedHashMap<>(); result.put("messageId", row.get("message_id")); result.put("role", row.get("role")); result.put("content", row.get("content")); result.put("taskId", row.get("task_id")); result.put("createdAt", json.instant(json.number(row.get("created_at_ms")))); return result;
        });
    }
    private Map<String, Object> pageRows(String id, String table, String key, String cursor, int requestedLimit, java.util.function.Function<Map<String, Object>, Map<String, Object>> map) {
        int limit = Math.max(1, Math.min(100, requestedLimit));
        long after = 0; String afterId = "";
        if (cursor != null && !cursor.isBlank()) {
            try { String[] pair = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", 2); after = Long.parseLong(pair[0]); afterId = pair[1]; if (afterId.length() > 128) throw new IllegalArgumentException(); }
            catch (Exception ex) { throw StudioException.invalid("分页游标无效"); }
        }
        var rows = repository.jdbc.queryForList("SELECT * FROM " + table + " WHERE work_id=? AND (created_at_ms>? OR (created_at_ms=? AND " + key + ">?)) ORDER BY created_at_ms," + key + " LIMIT " + (limit + 1), id, after, after, afterId);
        boolean more = rows.size() > limit; if (more) rows = rows.subList(0, limit);
        String next = null; if (more && !rows.isEmpty()) { var last = rows.get(rows.size() - 1); next = Base64.getUrlEncoder().withoutPadding().encodeToString((last.get("created_at_ms") + ":" + last.get(key)).getBytes(StandardCharsets.UTF_8)); }
        return json.page(rows.stream().map(map).toList(), next);
    }
    Map<String, Object> runView(String owner, Map<String, Object> work, Map<String, Object> row) {
        String taskId = json.text(row.get("run_id"));
        var state = queries.findWorkflow(taskId, owner);
        Map<String, Object> run = new LinkedHashMap<>(); run.put("runId", taskId); run.put("taskId", taskId); run.put("asyncWorkflowId", row.get("async_workflow_id")); run.put("sessionId", work.get("session_id")); run.put("contextRevision", row.get("context_revision")); run.put("createdAt", json.instant(json.number(row.get("created_at_ms"))));
        if (state.isPresent()) { run.put("rawStatus", state.get().workflowStatus()); run.put("errorCode", state.get().orchestration().get("errorCode")); run.put("finalAnswer", publicText(state.get().finalAnswer())); }
        else { var status = workflows.queryStatus(json.text(row.get("async_workflow_id")), owner); run.put("rawStatus", status == null ? "UNKNOWN" : status.status()); }
        return run;
    }
    Map<String, Object> receipt(String owner, String id, String requestId, String expectedHash) {
        var command = repository.command(owner, id, requestId); if (command == null) return null;
        if (expectedHash != null && !expectedHash.equals(command.get("request_hash"))) throw StudioException.conflict("COMMAND_CONFLICT", "同一请求编号不能用于不同操作内容");
        return json.read(json.text(command.get("receipt_json")));
    }
    void recordReceipt(String owner, String id, String requestId, String hash, String kind, String status, Map<String, Object> result) {
        Map<String, Object> receipt = new LinkedHashMap<>(result); receipt.put("requestId", requestId); receipt.put("workId", id); receipt.put("status", status);
        long now = System.currentTimeMillis();
        repository.jdbc.update("INSERT INTO studio_command(work_id,owner_id,request_id,request_hash,kind,status,receipt_json,created_at_ms,updated_at_ms) VALUES (?,?,?,?,?,?,?,?,?)", id, owner, requestId, hash, kind, status, json.write(receipt), now, now);
    }
    Map<String, Object> basic(Map<String, Object> row) {
        Map<String, Object> result = summary(row); result.put("sessionId", row.get("session_id")); result.put("revision", row.get("revision")); result.put("contextRevision", row.get("context_revision")); result.put("context", json.read(json.text(row.get("context_json")))); result.put("activeRun", null); result.put("pendingInput", null); result.put("pendingDecisions", List.of()); result.put("artifactSummaries", List.of()); result.put("allowedActions", List.of()); result.put("uiState", uiView(row)); return result;
    }
    private Map<String, Object> summary(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("workId", row.get("work_id")); result.put("title", row.get("title")); result.put("goal", row.get("goal")); result.put("updatedAt", json.instant(json.number(row.get("updated_at_ms")))); return result;
    }
    private Map<String, Object> uiView(Map<String, Object> row) { var ui = json.read(json.text(row.get("ui_json"))); ui.put("uiRevision", row.get("ui_revision")); return ui; }
    private String publicText(String value) {
        if (value == null) return "";
        return value.replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1[已隐藏]")
                .replaceAll("(?i)((?:ticket|accessToken|refreshToken)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s\\\"'&,}]+", "$1[已隐藏]");
    }
}
