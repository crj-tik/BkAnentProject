# 压测基线与验收清单

## 脚本

- [scripts/load/supervisor-task-load.ps1](/D:/project/BkAnentProject/BkAnentProject/scripts/load/supervisor-task-load.ps1)
- [scripts/load/supervisor-workflow-async-load.ps1](/D:/project/BkAnentProject/BkAnentProject/scripts/load/supervisor-workflow-async-load.ps1)
- [scripts/load/supervisor-parallel-load.ps1](/D:/project/BkAnentProject/BkAnentProject/scripts/load/supervisor-parallel-load.ps1)
- [scripts/load/supervisor-approval-recovery-load.ps1](/D:/project/BkAnentProject/BkAnentProject/scripts/load/supervisor-approval-recovery-load.ps1)

## 建议顺序

1. 单 Agent 任务链
2. Workflow Async 链
3. 并行链
4. 审批恢复链
5. 灰度/限流联合压测

## 执行说明

单 Agent：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load/supervisor-task-load.ps1
```

Workflow Async：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load/supervisor-workflow-async-load.ps1
```

并行链：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load/supervisor-parallel-load.ps1
```

审批恢复链：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/load/supervisor-approval-recovery-load.ps1
```

## 重点观测

- `GET /agent/supervisor/event-audit`
- `GET /agent/supervisor/diagnostics`
- `bk.agent.graph.subgraph.*`
- `bk.agent.async.task.*`
- `bk.agent.async.workflow.*`
- `bk.notification.workflow.*`

## 最小验收

- `supervisor/tasks` 成功率不低于 `99%`
- `supervisor/workflows/async` 稳定返回 `asyncWorkflowId`
- 并行链无重复 handoff、无重复 artifact 持久化
- 审批恢复链能完成 `REJECTED -> regenerate -> APPROVED`
- `event-audit` 中可查询到 `grayStrategyVersion`
- 限流命中时返回 `RATE_LIMITED`

## 建议记录

- 请求总数
- 成功数/失败数
- P95/P99
- 限流次数
- 审批回调次数
- 重试次数
- 灰度命中次数
