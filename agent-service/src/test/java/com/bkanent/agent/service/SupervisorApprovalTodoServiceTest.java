package com.bkanent.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bkanent.agent.entity.AgentWorkflowCheckpointEntity;
import com.bkanent.agent.mapper.AgentWorkflowCheckpointMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审批待办分页单测：核心判定是「任务最新 checkpoint 仍为 WAITING 才算待办」——
 * 已恢复/已完成任务的历史 WAITING 行必须被排除（见 LR-15）。
 */
class SupervisorApprovalTodoServiceTest {

    private AgentWorkflowCheckpointMapper checkpointMapper;
    private SupervisorApprovalTodoService service;

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "approval-todo-test"),
                AgentWorkflowCheckpointEntity.class);
    }

    @BeforeEach
    void setUp() {
        checkpointMapper = mock(AgentWorkflowCheckpointMapper.class);
        service = new SupervisorApprovalTodoService(checkpointMapper, new ObjectMapper());
    }

    @Test
    void excludesResumedTasksWhoseHistoricalWaitingRowsRemain() {
        // task-1 在等（最新行 WAITING）；task-2 曾在等但已恢复（最新行 RUNNING）
        AgentWorkflowCheckpointEntity task1Old = waitingRow(1L, "task-1", 1, "7");
        AgentWorkflowCheckpointEntity task1Latest = waitingRow(2L, "task-1", 2, "7");
        AgentWorkflowCheckpointEntity task2Old = waitingRow(3L, "task-2", 1, "8");
        AgentWorkflowCheckpointEntity task2Resumed = resumedRow(4L, "task-2", 2, "8");

        // ① 候选 task 查询（只选 task_id）返回两行 WAITING（task-1 新旧两版本、task-2 历史行）
        when(checkpointMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(task1Old, task1Latest, task2Old));
        // ② 分组查询返回每个 task 的 MAX(id)
        when(checkpointMapper.selectMaps(any(Wrapper.class)))
                .thenReturn(List.of(
                        row("task-1", 2L),
                        row("task-2", 4L)));
        // ③ 批量取最新行
        when(checkpointMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(task1Latest, task2Resumed));

        Map<String, Object> result = service.listPendingApprovals(null, 1, 20);

        assertThat(result.get("total")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> approvals = (List<Map<String, Object>>) result.get("approvals");
        assertThat(approvals).hasSize(1);
        assertThat(approvals.get(0).get("taskId")).isEqualTo("task-1");
        assertThat(approvals.get(0).get("approvalId")).isEqualTo("appr-1");
        assertThat(approvals.get(0).get("ownerUserId")).isEqualTo("7");
        assertThat(approvals.get(0).get("title")).isEqualTo("发布房源需审批");
    }

    @Test
    void ownerFilterHidesOtherUsersTodos() {
        AgentWorkflowCheckpointEntity waiting = waitingRow(1L, "task-1", 1, "7");
        when(checkpointMapper.selectList(any(Wrapper.class))).thenReturn(List.of(waiting));
        when(checkpointMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of(row("task-1", 1L)));
        when(checkpointMapper.selectBatchIds(anyCollection())).thenReturn(List.of(waiting));

        Map<String, Object> mine = service.listPendingApprovals("7", 1, 20);
        Map<String, Object> others = service.listPendingApprovals("8", 1, 20);

        assertThat(mine.get("total")).isEqualTo(1L);
        assertThat(others.get("total")).isEqualTo(0L);
        assertThat((List<?>) others.get("approvals")).isEmpty();
    }

    @Test
    void paginationSlicesDeduplicatedTodos() {
        // 三个不同 task 都在等，owner 各异；不带 owner 过滤应全部返回并分页
        List<AgentWorkflowCheckpointEntity> waitingRows = new ArrayList<>();
        List<Map<String, Object>> maxRows = new ArrayList<>();
        List<AgentWorkflowCheckpointEntity> latestRows = new ArrayList<>();
        for (long i = 1; i <= 3; i++) {
            AgentWorkflowCheckpointEntity row = waitingRow(i, "task-" + i, 1, String.valueOf(6 + i));
            waitingRows.add(row);
            maxRows.add(row("task-" + i, i));
            latestRows.add(row);
        }
        when(checkpointMapper.selectList(any(Wrapper.class))).thenReturn(waitingRows);
        when(checkpointMapper.selectMaps(any(Wrapper.class))).thenReturn(maxRows);
        when(checkpointMapper.selectBatchIds(anyCollection())).thenReturn(latestRows);

        Map<String, Object> page1 = service.listPendingApprovals(null, 1, 2);
        Map<String, Object> page2 = service.listPendingApprovals(null, 2, 2);
        Map<String, Object> page3 = service.listPendingApprovals(null, 3, 2);

        assertThat(page1.get("total")).isEqualTo(3L);
        assertThat(((List<?>) page1.get("approvals"))).hasSize(2);
        assertThat(((List<?>) page2.get("approvals"))).hasSize(1);
        assertThat(((List<?>) page3.get("approvals"))).isEmpty();
    }

    @Test
    void noWaitingRowsReturnsEmptyResultWithoutFurtherQueries() {
        when(checkpointMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        Map<String, Object> result = service.listPendingApprovals("7", 1, 20);

        assertThat(result.get("total")).isEqualTo(0L);
        assertThat((List<?>) result.get("approvals")).isEmpty();
        assertThat(result.get("page")).isEqualTo(1);
        assertThat(result.get("pageSize")).isEqualTo(20);
    }

    @Test
    void unparseableSnapshotDegradesToIdOnlyView() {
        AgentWorkflowCheckpointEntity broken = waitingRow(1L, "task-1", 1, "7");
        broken.setSnapshotJson("not-json{{{");
        when(checkpointMapper.selectList(any(Wrapper.class))).thenReturn(List.of(broken));
        when(checkpointMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of(row("task-1", 1L)));
        when(checkpointMapper.selectBatchIds(anyCollection())).thenReturn(List.of(broken));

        // owner 过滤下：快照解析失败、owner 无法判定 → 不出现在任何人的列表（保守隐藏）
        Map<String, Object> filtered = service.listPendingApprovals("7", 1, 20);
        assertThat(filtered.get("total")).isEqualTo(0L);

        // 无 owner 过滤（治理诊断口径）：仍可见，降级为 ID 维度
        Map<String, Object> result = service.listPendingApprovals(null, 1, 20);
        assertThat(result.get("total")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> approvals = (List<Map<String, Object>>) result.get("approvals");
        // 快照解析失败时仍可见（ID 维度），只是没有标题/摘要
        assertThat(approvals.get(0).get("taskId")).isEqualTo("task-1");
        assertThat(approvals.get(0).get("approvalId")).isEqualTo("appr-1");
        assertThat(approvals.get(0)).doesNotContainKey("title");
        assertThat(approvals.get(0)).doesNotContainKey("ownerUserId");
    }

    private AgentWorkflowCheckpointEntity waitingRow(Long id, String taskId, int version, String stateUserId) {
        return row(id, taskId, version, stateUserId, "WAITING_USER_APPROVAL", "appr-1");
    }

    private AgentWorkflowCheckpointEntity resumedRow(Long id, String taskId, int version, String stateUserId) {
        return row(id, taskId, version, stateUserId, "RUNNING", "appr-2");
    }

    private AgentWorkflowCheckpointEntity row(Long id, String taskId, int version, String stateUserId,
                                              String status, String approvalId) {
        AgentWorkflowCheckpointEntity entity = new AgentWorkflowCheckpointEntity();
        entity.setId(id);
        entity.setTaskId(taskId);
        entity.setSessionId("session-" + taskId);
        entity.setTraceId("trace-" + taskId);
        entity.setWorkflowStatus(status);
        entity.setPendingApprovalId(approvalId);
        entity.setCheckpointVersion(version);
        entity.setSnapshotJson(snapshotJson(stateUserId, taskId, approvalId));
        return entity;
    }

    private String snapshotJson(String stateUserId, String taskId, String approvalId) {
        Map<String, Object> approval = new HashMap<>();
        approval.put("approvalId", approvalId);
        approval.put("taskId", taskId);
        approval.put("sessionId", "session-" + taskId);
        approval.put("title", "发布房源需审批");
        approval.put("summary", "经纪人提交的房源发布计划等待确认");
        approval.put("approveNextNode", "complete");
        approval.put("rejectNextNode", "regenerate");
        approval.put("terminateNextNode", "cancel");
        approval.put("retryCount", 0);
        approval.put("maxRetryCount", 3);
        Map<String, Object> state = new HashMap<>();
        state.put("userId", stateUserId);
        state.put("pendingApproval", approval);
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("version", 1);
        envelope.put("state", state);
        try {
            return new ObjectMapper().writeValueAsString(envelope);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, Object> row(String taskId, Long latestRowId) {
        Map<String, Object> row = new HashMap<>();
        row.put("task_id", taskId);
        row.put("latest_row_id", latestRowId);
        return row;
    }
}
