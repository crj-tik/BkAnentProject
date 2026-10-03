# Proposal

## Why

当前 Supervisor 默认通过入口关键词、领域默认 intent 和业务交接条件决定 Agent，偏离了用户原始设计：正常请求应由配有 A2A/MCP 能力描述的 LLM 决定调用，确定的跨 Agent/工具流程应属于本次显式指定的 skill。仅开启现有 LLM JSON 规划不能消除固定路由骨架，也不能保证指定 skill 的执行约束。

## What Changes

- 建立统一的 Supervisor 能力目录，将已发现 A2A Agent、静态/动态 MCP 工具及必要本地工具转换为有稳定标识、description、输入 schema 的模型可调用工具；正常请求进入 LLM 的工具调用循环。
- **BREAKING**：正常请求不再通过关键词、默认 listing Agent、领域默认 intent、trade→contract 条件或 `nextHints` 自动决定业务调用；LLM 不可用时明确失败或等待恢复，不回到关键词分流。
- 增加请求级 `skill` 选择和两类执行技能：`instruction` 为专项指引和显式能力范围；`workflow` 以技能文件声明目标、依赖、条件、数据映射和审批节点，通用执行器按声明执行。固定流程以结构化步骤保证，不能只靠 prompt。
- 知识技能、模型主动选用的普通技能和旧 `skillHint` 保持独立语义；关键词可辅助知识提示，不能在请求入口隐式切换执行模式。
- Supervisor 和 Subagent 共享显式技能契约：指定技能应校验所属 Agent/版本、固定本次执行的有效能力边界，并拒绝缺失技能、能力和越界调用；旧 hint 仍为可覆盖的建议。
- 复用现有 A2A Message/Task/Artifact、权限、审批、checkpoint、异步租约和 SSE；增加可恢复的模型/工具执行状态与调用账本，避免审批或进程恢复重复执行副作用。
- 修正动态目录的 Agent Card 技能/能力保真及 endpoint 缓存刷新；检查 Nacos Agent Registry 版本兼容性，作为可靠能力发现的前置工作。

## Capabilities

### New Capabilities

- `supervisor-tool-orchestration`：LLM 根据统一能力目录自主选择 A2A/MCP/本地工具，含动态刷新、执行治理和恢复。
- `explicit-skill-execution`：请求级显式技能选择、指引技能和固定流程技能、能力限制及版本固定。
- `subagent-skill-contract`：A2A 显式技能与建议 hint 的区分、Subagent 执行约束和向后兼容。

### Modified Capabilities

- `supervisor-domain-catalog`：目录由业务路由入口改为能力描述与合法性校验来源；调整冷启动、规划、并行和 nextHint 语义，删除入口关键词默认分流要求。

## Impact

- 主要模块：`agent-service`、`common`、`common-skill`、`common-a2a`；`common-config-manager` 配合 MCP 稳定身份与动态能力刷新；九个领域 Subagent 的共享装配、技能和卡片配置需同步核对。
- API：现有 Supervisor 同步/异步入口增补可选 `skill`；`/agent/chat` 作为兼容入口逐步接入同一执行核心。保留现有任务/会话/产物/审批查询和 SSE 入口，新增执行模式等观测字段。
- 存储：checkpoint 内容增补执行模式、技能快照和工具循环状态；拟增加独立工具调用账本及技能快照存储，迁移脚本在实施阶段提供。
- 规格冲突：主规格仍要求关键词兜底和 nextHint 自动交接，本变更提供明确 delta；未完成的 `migrate-supervisor-graph-approval-routing`、`create-common-skill-module` 需按新边界协调，避免继续扩展旧入口路由。
- 保留领域职责：访谈治理面可参与 A2A 委托；访谈运行面遵守 LR-3/4/5/8/13，不迁移高频话轮、状态机或公开 MCP 边界。
- 本次交付仅为待实施方案、规格和任务。业务代码、运行配置、数据库及部署均未实施；所有实现任务保持未勾选。
