package com.bkanent.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bkanent.agent.entity.AgentWorkflowCheckpointEntity;
import com.bkanent.agent.mapper.AgentWorkflowCheckpointMapper;
import com.bkanent.common.agent.ApprovalRequest;
import com.bkanent.common.agent.WorkflowStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审批待办查询：取「每个任务最新 checkpoint 且仍为 WAITING_USER_APPROVAL」的快照。
 *
 * <p>checkpoint 表按 (task_id, checkpoint_version) 追加写：任务进入审批等待时写入
 * WAITING 行，恢复执行后又写入新状态的行，历史 WAITING 行不删除。因此「当前在等」
 * 的判定依据是该 task 最新一行的状态，而非任何 WAITING 行的存在（见 LR-15）。</p>
 *
 * <p>查询路径：① 曾经 WAITING 过的 task 集合（只取 task_id 列，不碰 LONGTEXT 快照）；
 * ② 这些 task 的 MAX(id) 分组查询定位最新行；③ 批量取行后二次过滤状态；
 * ④ 解析快照还原审批卡与 state.userId（owner 过滤口径与单任务查询一致）。</p>
 */
@Service
public class SupervisorApprovalTodoService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int IN_CLAUSE_CHUNK = 500;

    private final AgentWorkflowCheckpointMapper checkpointMapper;
    private final ObjectMapper objectMapper;

    public SupervisorApprovalTodoService(AgentWorkflowCheckpointMapper checkpointMapper,
                                         ObjectMapper objectMapper) {
        this.checkpointMapper = checkpointMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 审批待办列表（分页）：owner 过滤（state.userId，与单任务工作流查询同口径），
     * 最新等待优先。userId 为空时返回全部待办（供治理面诊断用途）。
     */
    public Map<String, Object> listPendingApprovals(String userId, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        // ① 候选 task：曾经 WAITING 过的（只选 task_id，避免拖动 LONGTEXT 快照列）
        List<AgentWorkflowCheckpointEntity> waitingRows = checkpointMapper.selectList(
                new LambdaQueryWrapper<AgentWorkflowCheckpointEntity>()
                        .select(AgentWorkflowCheckpointEntity::getTaskId)
                        .eq(AgentWorkflowCheckpointEntity::getWorkflowStatus,
                                WorkflowStatus.WAITING_USER_APPROVAL.name()));
        if (waitingRows.isEmpty()) {
            return emptyResult(safePage, safeSize);
        }
        Set<String> candidateTaskIds = new HashSet<>();
        for (AgentWorkflowCheckpointEntity row : waitingRows) {
            candidateTaskIds.add(row.getTaskId());
        }

        // ② 最新行定位：MAX(id) 即该 task 最新写入（id 自增与版本递增同序）
        Map<String, Long> latestRowIdByTask = new LinkedHashMap<>();
        for (List<String> chunk : partition(candidateTaskIds)) {
            QueryWrapper<AgentWorkflowCheckpointEntity> groupQuery = new QueryWrapper<>();
            groupQuery.select("task_id", "MAX(id) AS latest_row_id")
                    .in("task_id", chunk)
                    .groupBy("task_id");
            for (Map<String, Object> row : checkpointMapper.selectMaps(groupQuery)) {
                latestRowIdByTask.put(String.valueOf(row.get("task_id")),
                        ((Number) row.get("latest_row_id")).longValue());
            }
        }

        // ③ 批量取最新行，二次过滤：仅最新行仍是 WAITING 的才算待办（已恢复任务的
        //    历史 WAITING 行在这里被排除）
        List<AgentWorkflowCheckpointEntity> todos = new ArrayList<>();
        for (List<Long> chunk : partition(latestRowIdByTask.values())) {
            for (AgentWorkflowCheckpointEntity entity : checkpointMapper.selectBatchIds(chunk)) {
                if (WorkflowStatus.WAITING_USER_APPROVAL.name().equals(entity.getWorkflowStatus())
                        && entity.getPendingApprovalId() != null) {
                    todos.add(entity);
                }
            }
        }

        // ④ owner 过滤 + 视图装配
        todos.sort((a, b) -> Long.compare(b.getId(), a.getId()));
        List<Map<String, Object>> items = new ArrayList<>();
        for (AgentWorkflowCheckpointEntity entity : todos) {
            Map<String, Object> view = toTodoView(entity);
            if (userId != null && !userId.isBlank()
                    && !userId.equals(view.get("ownerUserId"))) {
                continue; // 非本人工作流的待办不可见（与单任务查询 owner 制一致）
            }
            items.add(view);
        }

        long total = items.size();
        List<Map<String, Object>> pageItems = items.subList(
                (int) Math.min((long) (safePage - 1) * safeSize, total),
                (int) Math.min((long) safePage * safeSize, total));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("approvals", pageItems);
        result.put("total", total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        return result;
    }

    private Map<String, Object> toTodoView(AgentWorkflowCheckpointEntity entity) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("taskId", entity.getTaskId());
        view.put("sessionId", entity.getSessionId());
        view.put("traceId", entity.getTraceId());
        view.put("approvalId", entity.getPendingApprovalId());
        view.put("workflowStatus", entity.getWorkflowStatus());
        view.put("waitingSince", entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
        ParsedSnapshot snapshot = readSnapshot(entity);
        if (snapshot.stateUserId() != null) {
            view.put("ownerUserId", snapshot.stateUserId());
        }
        ApprovalRequest request = snapshot.approvalRequest();
        if (request != null) {
            view.put("title", request.title());
            view.put("summary", request.summary());
            view.put("approvalType", request.approvalType());
            view.put("retryCount", request.retryCount());
            view.put("maxRetryCount", request.maxRetryCount());
        }
        return view;
    }

    /** 解析快照信封：还原 state.userId（owner 口径）与 pendingApproval 审批卡；失败静默降级。 */
    private ParsedSnapshot readSnapshot(AgentWorkflowCheckpointEntity entity) {
        try {
            String snapshot = entity.getSnapshotJson();
            if (snapshot == null || snapshot.isBlank()) {
                return new ParsedSnapshot(null, null);
            }
            Map<String, Object> envelope = objectMapper.readValue(snapshot,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            if (!(envelope.get("state") instanceof Map<?, ?> state)) {
                return new ParsedSnapshot(null, null);
            }
            String stateUserId = state.get("userId") == null ? null : String.valueOf(state.get("userId"));
            ApprovalRequest request = null;
            if (state.get("pendingApproval") instanceof Map<?, ?> raw) {
                Map<String, Object> fields = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    fields.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                request = new ApprovalRequest(
                        text(fields.get("approvalId")),
                        text(fields.get("taskId")),
                        text(fields.get("sessionId")),
                        text(fields.get("approvalType")),
                        text(fields.get("subjectType")),
                        text(fields.get("subjectId")),
                        integer(fields.get("subjectVersion")),
                        text(fields.get("title")),
                        text(fields.get("summary")),
                        null,
                        text(fields.get("approveNextNode")),
                        text(fields.get("rejectNextNode")),
                        text(fields.get("terminateNextNode")),
                        integer(fields.get("retryCount")),
                        integer(fields.get("maxRetryCount")),
                        text(fields.get("traceId"))
                );
            }
            return new ParsedSnapshot(stateUserId, request);
        } catch (Exception exception) {
            return new ParsedSnapshot(null, null);
        }
    }

    private Map<String, Object> emptyResult(int page, int pageSize) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("approvals", List.of());
        result.put("total", 0L);
        result.put("page", page);
        result.put("pageSize", pageSize);
        return result;
    }

    private <T> List<List<T>> partition(Iterable<T> values) {
        List<List<T>> chunks = new ArrayList<>();
        List<T> current = new ArrayList<>();
        for (T value : values) {
            current.add(value);
            if (current.size() >= IN_CLAUSE_CHUNK) {
                chunks.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private record ParsedSnapshot(String stateUserId, ApprovalRequest approvalRequest) {
    }
}
