# Supervisor 旧执行链清理记录

2026-10-05 按 OpenSpec 7.4 完成旧 Supervisor Graph 执行链清理。对本机 Docker 验收 MySQL 做只读核对，发现 7 条旧流程验收记录，3 条 COMPLETED、4 条 FAILED，均为终态；未发现旧异步任务、旧工作流或仍待处理的旧任务。该数据库是独立验收库，不能代表生产环境排空情况。

## 清理边界

- 删除旧 Graph factories/holders、路由/计划/恢复节点、旧 intent/default-route 服务及仅供旧执行器使用的配置。
- Supervisor 同步与异步服务直接调用 `SupervisorToolLoopRunner`；新执行只接受 `llm-tools-v1` checkpoint envelope，旧 graphName、无版本 raw snapshot 与其他 runner 版本均不恢复执行。
- 保留 `agent_workflow_checkpoint` 历史行、`DbGraphCheckpointStore` 的只读结果查询、审批调用账本和新 runner checkpoint 存储。历史 checkpoint 不能触发旧图或重新调用 Agent。
- 保留动态 Agent Registry / Card 能力、MCP 工具目录、技能快照、审批 claim 与新 runner 的调用级恢复。
- 保留 KI-19：Markdown skill 正文的步骤顺序由模型遵循，平台只强制校验能力、参数、权限、身份、预算与审批，不提供业务步骤强保证。

## 验收依据

本机 SQL 检查查询每个 `task_id` 的最新 checkpoint，不把历史 WAITING 行误认为当前待办；旧流程验收记录均已终态。数据库数据未删除。源码审计、严格 OpenSpec 校验、Maven 编译和测试用于验证清理后的执行路径与规格一致。生产部署如仍保留更早版本的未完成旧任务，不具备本次已删除的旧 runner 恢复能力，应在发布前按生产数据安排任务处置；本记录没有声称生产数据已检查。
