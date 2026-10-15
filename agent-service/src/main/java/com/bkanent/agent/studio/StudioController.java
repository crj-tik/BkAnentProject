package com.bkanent.agent.studio;

import com.bkanent.common.model.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.function.Function;

@RestController
@RequestMapping("/agent/studio/v1")
public class StudioController {
    private final StudioIdentity identity;
    private final StudioWorkspaceService works;
    private final StudioArtifactsService artifacts;
    private final StudioCapabilityService capabilities;
    private final StudioCommandService commands;
    public StudioController(StudioIdentity identity, StudioWorkspaceService works, StudioArtifactsService artifacts, StudioCapabilityService capabilities, StudioCommandService commands) {
        this.identity = identity; this.works = works; this.artifacts = artifacts; this.capabilities = capabilities; this.commands = commands;
    }
    private <T> ApiResponse<T> guarded(Function<String, T> action) {
        String owner = identity.owner(); T result = action.apply(owner);
        if (!owner.equals(identity.owner())) throw StudioException.missing();
        return ApiResponse.ok(result);
    }
    @GetMapping("/capabilities") public ApiResponse<List<Map<String, Object>>> capabilities() { return guarded(owner -> capabilities.list(identity.administrator())); }
    @PostMapping("/works") public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) { return guarded(owner -> works.create(owner, body)); }
    @GetMapping("/works") public ApiResponse<Map<String, Object>> recent(@RequestParam(required = false) String keyword, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
                                                                     @RequestParam(required = false) String status, @RequestParam(required = false) String needsDecision) {
        if (status != null || needsDecision != null) throw StudioException.invalid("请从待决策入口读取实时待办；当前工作列表支持标题和目标查询");
        return guarded(owner -> works.recent(owner, keyword, cursor, limit));
    }
    @GetMapping("/works/{id}") public ApiResponse<Map<String, Object>> work(@PathVariable String id) {
        return guarded(owner -> {
            var snapshot = works.snapshot(owner, id);
            var page = artifacts.list(owner, id, null, 100);
            var summaries = ((List<?>) page.get("items")).stream().map(item -> {
                Map<String, Object> summary = new LinkedHashMap<>(); Map<?, ?> data = (Map<?, ?>) item;
                for (String key : List.of("seriesId", "revisionId", "version", "sourceArtifactId", "title", "type", "schemaVersion", "freshness")) summary.put(key, data.get(key));
                return summary;
            }).toList(); snapshot.put("artifactSummaries", summaries); return snapshot;
        });
    }
    @PostMapping("/works/{id}/context") public ApiResponse<Map<String, Object>> context(@PathVariable String id, @RequestBody Map<String, Object> body) { return guarded(owner -> works.context(owner, id, body)); }
    @PostMapping("/works/{id}/ui-state") public ApiResponse<Map<String, Object>> ui(@PathVariable String id, @RequestBody Map<String, Object> body) { return guarded(owner -> works.ui(owner, id, body)); }
    @PostMapping("/works/{id}/runs") public ApiResponse<Map<String, Object>> submit(@PathVariable String id, @RequestBody Map<String, Object> body) { return guarded(owner -> { artifacts.syncLatest(owner, id); return works.submit(owner, id, body); }); }
    @GetMapping("/works/{id}/runs") public ApiResponse<Map<String, Object>> runs(@PathVariable String id, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) { return guarded(owner -> works.runs(owner, id, cursor, limit)); }
    @GetMapping("/works/{id}/messages") public ApiResponse<Map<String, Object>> messages(@PathVariable String id, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "100") int limit) { return guarded(owner -> works.messages(owner, id, cursor, limit)); }
    @GetMapping("/works/{id}/artifacts") public ApiResponse<Map<String, Object>> artifacts(@PathVariable String id, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "100") int limit) { return guarded(owner -> artifacts.list(owner, id, cursor, limit)); }
    @GetMapping("/artifact-series/{seriesId}") public ApiResponse<Map<String, Object>> artifact(@PathVariable String seriesId, @RequestParam(required = false) Long version) { return guarded(owner -> artifacts.get(owner, seriesId, version)); }
    @PostMapping("/artifact-series/{seriesId}/revisions") public ApiResponse<Map<String, Object>> save(@PathVariable String seriesId, @RequestBody Map<String, Object> body) { return guarded(owner -> artifacts.save(owner, seriesId, body)); }
    @GetMapping("/artifact-series/{seriesId}/revisions") public ApiResponse<Map<String, Object>> revisions(@PathVariable String seriesId, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "100") int limit) { return guarded(owner -> artifacts.revisions(owner, seriesId, cursor, limit)); }
    @PostMapping("/works/{id}/commands") public ApiResponse<Map<String, Object>> command(@PathVariable String id, @RequestBody Map<String, Object> body) { return guarded(owner -> commands.execute(owner, id, body)); }
    @GetMapping("/works/{id}/commands/{requestId}") public ApiResponse<Map<String, Object>> receipt(@PathVariable String id, @PathVariable String requestId) { return guarded(owner -> commands.receipt(owner, id, requestId)); }
}
