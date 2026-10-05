# Proposal

## Why

当前 Subagent 已能由 LLM 读取 skill 正文并选择本域工具，但 Supervisor 默认仍通过入口关键词、领域计划和自动交接决定 Agent，跨服务决策没有交给配有真实 A2A/MCP 工具的 LLM。2026-10-04 用户最终选择“模型遵循技能正文”的第一种方案：统一 Supervisor 模型工具循环，指定 skill 后仍由模型理解请求，不引入技能顺序校验或 DAG 执行器。

## What Changes

- 建立统一能力目录，将真实可用且授权可见的 A2A Agent、静态/动态 MCP 和必要本地工具转换为有稳定身份、description、输入 schema 和执行回调的模型工具；模型根据实际结果逐轮选择调用、追问或结束。
- **BREAKING**：新请求停用入口关键词分流、默认 listing/default intent、固定业务并行、trade→contract 自动交接和 nextHints 自动 handoff。LLM 不可用时同模式有界重试或明确失败，不切换到关键词路径。
- 接入 Supervisor 技能目录与 `skill` 加载工具。未指定技能时模型可按 description 自主加载普通指引技能；本次显式指定技能时平台在首轮 LLM 前校验、固定版本、加载全文和能力范围，模型仍理解原始需求、提取参数并处理缺失/冲突。
- 技能保持 frontmatter + Markdown 正文。正文中的调用对象、顺序、条件和结果使用方法由模型遵循；平台严格约束工具范围、参数、身份、权限和审批，**不检查业务步骤顺序、前置步骤完成或流程完成条件**，不增加 workflow DSL、步骤调度器或 DAG 子图。
- 将 Supervisor Graph 改为通用的上下文准备、单轮模型决策、技能加载、调用校验、审批、工具执行、结果回写和结束/恢复循环。A2A/MCP 通过统一工具执行节点调用，不为每个 Agent、技能或业务链建立节点；模型节点不能偷偷自动执行工具。
- Subagent 复用已有 ReactAgent、SkillTool 和领域工具，仅同步显式 skillSelection 契约、版本快照、能力范围校验和 Agent Card 技能映射；旧 skillHint 保持建议语义。父级技能不自动强制传给所有子 Agent。
- 复用 A2A Message/Task/Artifact、权限、异步租约、审批和 SSE，增加调用级 checkpoint、调用账本和技能快照，避免恢复时重复已完成调用。技能顺序依赖模型的明确限制进入验收与已知问题说明。
- 修正 Agent Card 描述/技能/能力保真、endpoint 缓存更新和 MCP 重名问题，并验证 Nacos Agent Registry 部署版本，保证工具目录对应真实执行能力。

## Capabilities

### New Capabilities

- `supervisor-tool-orchestration`：基于真实统一能力目录的 Supervisor LLM 工具循环、通用 Graph、执行治理与恢复。
- `explicit-skill-execution`：模型自主/用户显式技能加载、指定后持续理解需求、正文流程指引、严格能力范围及版本固定；不提供顺序强保证。
- `subagent-skill-contract`：显式技能与旧 hint 的区分、子执行的技能范围和版本约束、既有领域 ReAct 兼容。

### Modified Capabilities

- `supervisor-domain-catalog`：领域目录用于能力描述、动态发现和合法性校验；改写旧领域计划、并行和 nextHint 语义，删除新入口关键词默认分流要求。

## Impact

- 主要模块：`agent-service`、`common`、`common-skill`、`common-a2a`；`common-config-manager` 配合动态 MCP 稳定身份。九个领域 Subagent 接入共享契约和卡片映射，不重写领域执行逻辑。
- API：Supervisor 同步/异步入口增补可选顶层 `skill={name,version}` 和 `continueRunId`，以 WAITING_USER_INPUT 表达追问；普通 `/agent/chat` 逐步接入同一核心并保留既有字段和 `allowMcp`。任务/审批/产物查询及 SSE 入口继续使用，新增状态与续接语义在客户端迁移说明中声明。
- 存储：版本化 checkpoint 增补模型消息、待调用、有效技能快照和预算；拟增加调用账本及技能快照存储，不增加技能步骤/依赖/流程进度表。
- 兼容：旧任务按旧 runner 恢复；新请求采用 AUTO/EXPLICIT_SKILL，同一通用 Graph 执行。旧技能资源与 hint 兼容，显式技能的范围策略必须明确；主规格和相交未完成变更在实施阶段按本 delta 协调。
- 领域边界：访谈治理面可接 A2A；高频话轮、确定性决策、状态机和只读 MCP 边界保持 LR-3/4/5/8/13 的既定职责。
- 本版替代本变更上一版 instruction/workflow 双执行器设计；第一种方案的限制是模型可能偏离正文顺序，不承诺平台强制固定流程。本次仅更新规划文档、认知清单和路线图，代码、配置、SQL 与部署未修改，全部实施任务保持未完成。
