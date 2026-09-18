## Purpose

该能力定义 Supervisor 如何使用可恢复的状态图编排多 Agent 任务，使条件路由、审批中断、人工决策恢复、并行执行和完成状态都具有一致、可测试且可持久化的行为。

## ADDED Requirements

### Requirement: Supervisor tasks SHALL execute through one canonical workflow graph

Supervisor 的同步任务、工作流任务以及对应的异步执行 SHALL 使用同一套规范化工作流状态和节点转换规则。入口服务可以负责鉴权、限流和提交任务，但不得根据业务状态自行决定单 Agent、并行、审批、handoff 或完成节点。

#### Scenario: Single-agent task is routed by the graph

- **WHEN** 一个经过校验的计划只包含一个目标 Agent 且不需要审批
- **THEN** Graph SHALL 选择该 Agent、执行 Agent 调用、持久化结果并进入完成节点
- **AND** 入口 Service SHALL 返回 Graph 产生的最终状态和结果

#### Scenario: Parallel task is routed by the graph

- **WHEN** 一个经过校验的计划包含两个或更多允许的并行领域
- **THEN** Graph SHALL 创建并执行并行分支
- **AND** 所有分支结果 SHALL 在进入后续路由或完成节点前完成聚合

#### Scenario: Invalid workflow plan is rejected before execution

- **WHEN** 计划包含未注册 Agent、未允许的领域、非法步骤或不允许的状态转换
- **THEN** Graph SHALL 将任务置为失败并返回可诊断的计划校验错误
- **AND** 任何子 Agent SHALL NOT be invoked

### Requirement: Approval SHALL pause the graph before protected execution

当计划或系统规则要求人工审批时，Graph SHALL 在受保护的业务节点之前创建审批请求并暂停执行。进入等待状态后，后续 Agent、handoff、发布或完成节点不得执行，审批状态 SHALL 与当前 Graph 线程和任务绑定。

#### Scenario: Workflow enters approval wait state

- **WHEN** 当前路由要求人工审批
- **THEN** Graph SHALL 持久化包含审批请求、待执行节点、任务标识、线程标识和重试信息的状态
- **AND** SHALL 返回 `WAITING_USER_APPROVAL`
- **AND** SHALL NOT invoke the protected downstream node

#### Scenario: Workflow without approval continues

- **WHEN** 当前计划不要求人工审批且没有系统规则插入审批点
- **THEN** Graph SHALL bypass the approval node
- **AND** SHALL continue to the selected execution node

### Requirement: Approval decisions SHALL select the next graph transition

审批回调 SHALL 只通过当前任务关联的 Graph 线程恢复流程。Graph SHALL 根据审批结果和审批动作选择下一节点，而不是由入口 Service 重新拼接流程。

#### Scenario: Approval is accepted

- **WHEN** 回调中的审批标识、任务标识和线程标识匹配当前等待状态，且决策为 `APPROVED`
- **THEN** Graph SHALL resume from the recorded next-step decision
- **AND** SHALL execute the approved next node exactly once

#### Scenario: Approval is rejected with regeneration

- **WHEN** 决策为 `REJECTED` 且动作要求重新生成
- **THEN** Graph SHALL route to the regeneration path with the reviewer feedback
- **AND** SHALL preserve the previous attempt and increment the retry count

#### Scenario: Approval is terminated

- **WHEN** 决策为 `TERMINATED` 或人工选择取消
- **THEN** Graph SHALL route to cancellation/termination
- **AND** SHALL NOT invoke any downstream Agent

#### Scenario: Approval callback is invalid or duplicated

- **WHEN** 审批标识、任务标识、线程标识不匹配，或同一审批已经处理过
- **THEN** Graph SHALL reject the callback without changing the workflow state
- **AND** SHALL return a deterministic duplicate-or-invalid-approval error

### Requirement: Graph state SHALL survive process restart and support idempotent resume

处于运行中、等待审批、重试中或完成转换中的 Graph 状态 SHALL 使用持久化 Checkpointer 保存。`threadId`、`taskId` 和 `sessionId` 的映射 SHALL 稳定且可查询，服务重启或切换实例后可以恢复等待状态。

#### Scenario: Waiting workflow resumes after restart

- **WHEN** 服务在审批等待期间重启，随后收到合法审批回调
- **THEN** 系统 SHALL 根据持久化线程状态恢复 Graph
- **AND** SHALL 从审批记录的下一节点继续执行，而不是重新运行规划和已完成的 Agent 节点

#### Scenario: Resume request is retried

- **WHEN** 相同审批回调或相同幂等键被重复提交
- **THEN** 系统 SHALL 返回原处理结果或确定性的重复处理结果
- **AND** SHALL NOT 重复调用 Agent、重复创建 artifact 或重复推进 Graph

### Requirement: Graph execution SHALL expose observable node transitions

Graph SHALL 为规划、路由、审批等待、审批恢复、Agent 调用、并行聚合、handoff、失败和完成等关键节点产生可关联的事件或审计信息。每条记录 SHALL 至少能够关联 `taskId`、`traceId`、`sessionId` 和 Graph 节点标识。

#### Scenario: Graph node succeeds

- **WHEN** 一个 Graph 节点成功执行
- **THEN** 系统 SHALL 记录节点名称、开始时间、结束时间和任务关联标识

#### Scenario: Graph node fails

- **WHEN** 一个 Graph 节点抛出可恢复或不可恢复错误
- **THEN** 系统 SHALL 记录错误类别和节点上下文
- **AND** SHALL 按工作流策略进入重试、审批、失败或取消分支
