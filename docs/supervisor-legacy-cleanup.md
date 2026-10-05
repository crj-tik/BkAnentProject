# 旧 Supervisor 清理的待确认范围

2026-10-05：新请求已使用 llm-tools-v1；旧 checkpoint 的查询、审批与目标绑定已验证。任务 7.4 的文档与规格同步已完成，旧图删除尚未执行。当前仅有本机独立验收库，不能据此确认生产旧 run 已排空。

## 可核对的当前结果

- `DefaultOfficialSupervisorGraphFacade` 根据已有 run/timeline 区分新旧执行；新请求不走关键词、领域计划或默认 Agent。
- `SupervisorToolLoopGraph` 的固定通用拓扑不引用旧 Plan/Route/handoff 节点；nextHints 交回模型。
- 旧 graph/checkpoint 及审批恢复仍保留。已有 selectedAgentId 不允许因注销而更换业务目标，见 KI-27。
- README、路线图、LR-17/18、KI-17/18 与四份主规格已同步；KI-19 的正文顺序限制保留。

## 删除前所需证据

在实际部署数据库执行只读核对，检查每个旧 run 的最新记录，不能将任一历史 WAITING checkpoint 当成当前待办（LR-15）。以下查询以新 run 注册表区分存量任务，同时保留缺失状态的异常记录：

```sql
WITH latest_legacy AS (
    SELECT c.task_id, c.session_id, c.workflow_status, c.pending_approval_id,
           c.selected_agent_id, c.snapshot_json,
           ROW_NUMBER() OVER (
               PARTITION BY c.task_id
               ORDER BY c.checkpoint_version DESC, c.id DESC
           ) AS position_in_task
    FROM agent_workflow_checkpoint c
    WHERE c.deleted = 0
      AND NOT EXISTS (
          SELECT 1 FROM agent_orchestration_run r WHERE r.run_id = c.task_id
      )
)
SELECT task_id, session_id, workflow_status, pending_approval_id, selected_agent_id
FROM latest_legacy
WHERE position_in_task = 1
  AND COALESCE(workflow_status, 'UNKNOWN') NOT IN
      ('COMPLETED', 'FAILED', 'CANCELED', 'CANCELLED', 'TERMINATED');
```

还需核对异步队列、未完成审批、旧远端 Task 和提交结果未知的调用；FAILED 不能证明远端未产生副作用。记录环境、版本、时间、数量与核对结论，确认不再恢复旧 timeline 后才删除。

## 待确认的实施选择

1. 保留仅供旧 checkpoint 的恢复兼容代码，关闭的新请求旧路由入口保持关闭；生产排空后另行删除，并将本次 7.4 修改为这个明确范围。
2. 提供已排空的实际环境证据后继续原 7.4，删除旧 graph factories/holders、旧规划和自动 handoff 依赖及仅供其使用的配置，再运行编译、旧结果查询和新入口回归。

选择 1 会改变原任务“本次删除”的完成条件，需要用户明确确认；目前未将 7.4 勾选为完成。无论选择哪项，都不允许将新 run 交给旧关键词 runner，或删除仍承载旧审批恢复的节点。
