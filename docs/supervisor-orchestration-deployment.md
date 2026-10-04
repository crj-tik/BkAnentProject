# Supervisor 工具循环部署

先在目标库应用 `sql/migrations/20261004_supervisor_orchestration.sql`，再发布已支持精确技能 Card 的九服务，最后发布 Supervisor。新增表记录 run 租约、调用事实及技能快照，不含技能业务步骤状态。

新请求默认使用 `llm-tools-v1`，没有关键词、默认 listing/default intent 或 nextHints/trade 自动交接。已有旧 checkpoint 继续由旧 runner 读取。普通 chat 接入、远端对账和完整发布演练仍按当前 OpenSpec 清单实施，不能把本文件当作全部任务已经完成的证明。

请求可选 `skill: {name, version, owner, contentHash}`、`allowMcp`、`continueRunId`。不传 skill 为 AUTO。指定技能在模型前加载，模型仍理解 userMessage。补充输入必须提供新的 requestId 和原 continueRunId，只能续接同 owner 的 WAITING_USER_INPUT；原快照、请求政策与累计预算沿用。同 session 的其他新请求不继承显式技能。

`agent.supervisor.orchestration` 配置支持 max-rounds（12）、max-tool-calls（24）、max-concurrency（4）、model-retries（2）、approval-capabilities（稳定 capabilityId 集合）和 accepting（true）。context.requireApproval=true 要求对实际业务调用审批。暂停 accepting 仅停止接收新 run，恢复已有 run 不依赖该开关。

skill 与 request_input 每轮最多一个且须单独调用；混批不产生任何业务效果。审批 payload 给出 callId、capabilityId 和 argumentsHash。OUTCOME_UNKNOWN 停止模型继续推进，必须核对原调用，不应使用新 run 自动重发。
