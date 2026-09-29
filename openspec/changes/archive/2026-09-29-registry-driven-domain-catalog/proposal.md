# Proposal: registry-driven-domain-catalog

## Why

A2A Agent 已经通过 Nacos 动态注册（`agent-domains` 元数据 + Agent Card `supportedDomains`），但 Supervisor 的领域词表仍然焊死在代码里：LLM 规划 prompt（`SupervisorIntentPlanningService`）、计划校验白名单（`WorkflowPlanValidator`）、并行图分支拓扑（`OfficialSupervisorGraphFactory.PARALLEL_DOMAIN_NODES`）、三份互相已漂移的 `resolveIntent` switch、以及规则路由关键词表（`ParseIntentNode`/`PlanTaskNode`）各自维护一份静态副本。活例子：`compare-agent` 在 Nacos 注册完好、灰度配置也有它，但 LLM 规划不出 `domain=compare`、校验会拒绝、并行图路由不到——只能旁路调用。"注册表说有、Supervisor 说不认识"的漂移会随每个新 Agent 接入重复发生。

## What Changes

- 新增 `DomainCatalog` 单一派生点：从 `AgentRegistry` 聚合领域词表、默认 intent、Agent 描述，供规划、校验、编排、规则路由四处统一消费。
- LLM 规划 prompt 动态化：`SupervisorIntentPlanningService.systemPrompt()` 改为按 catalog 快照生成（稳定排序、注入每个 Agent 的 description/skills），删除写死的 7 域词表。
- 校验白名单派生化：`WorkflowPlanValidator` 的 `ALLOWED_DOMAINS` 改为实时查询 catalog；`<domain>_with_approval` 改为派生规则（后缀匹配 + 前缀在 catalog 中即合法）；`MAX_PARALLEL_DOMAINS` 配置化为 `agent.distributed.planning.max-parallel-domains`（默认 7，保持现状）。
- 并行图槽位化：`PARALLEL_DOMAIN_NODES` 的 7 个命名分支节点替换为 N 个通用槽位 `parallel_branch_0..N-1`（容量 `agent.distributed.catalog.branch-capacity`，默认 16），运行时按 domain 下标映射到槽位；空槽位不执行、不耗资源；图拓扑与领域词表彻底解耦，新增域不再需要改图、不需要重建 `CompiledGraph`。
- intent/hint 映射数据化：三份 `resolveIntent` switch 收编为 `catalog.resolveDefaultIntent(domain)`（Nacos 元数据 `agent-default-intent` → 配置 `default-intents` 种子表 → Agent Card `supportedSkills[0]` 三级回退）；`mapNextHint` 改为通用规则（hint 按 `.` 切出 domain 前缀 + catalog 成员校验 + 配置化 `hint-rewrites` 改写表）。
- 规则路由词表配置化：`ParseIntentNode` 关键词表移到 `agent.distributed.catalog.rule-routing.keywords` 配置（现有词做种子）；`PlanTaskNode` 的并行规则配置化。规则路由本期仍是加速层。
- 冷启动兜底显式化：`agent.distributed.catalog.cold-start-fallback-domains`（默认现有 7 域）仅在注册表首次成功刷新前生效，之后动态词表完全接管；治理视图暴露 `vocabularySource: COLD_START_FALLBACK | REGISTRY`。
- 新增 `docs/supervisor-routing-roadmap.md`：长期迭代路线图，首条记录"规则路由整体降级为 LLM 规划兜底、关键词只保留高置信场景"的既定方向。
- 无对外 API 变更；无 **BREAKING** 变更。`parallel_*` 分支节点名变为 `parallel_branch_*` 仅影响图内部可观测性（trace/流程图），不影响 checkpoint 恢复语义。

## Capabilities

### New Capabilities

- `supervisor-domain-catalog`: Supervisor 领域目录——从 Agent 注册表派生领域词表、默认 intent、Agent 能力描述，统一供给 LLM 规划 prompt、计划校验、并行图槽位路由与规则路由；包含冷启动兜底语义、词表来源可观测性与规则路由配置化。

### Modified Capabilities

（无——现有主 specs 中无 supervisor 路由相关能力，本次全部为新增能力。）

## Impact

- **代码**（均在 `agent-service`）：
  - 新增 `com.bkanent.agent.catalog.DomainCatalog` 及实现。
  - 修改 `SupervisorIntentPlanningService`（prompt 动态生成）、`WorkflowPlanValidator`（白名单派生）、`OfficialSupervisorGraphFactory`（槽位化 + 收编 resolveIntent/mapNextHint）、`ParseIntentNode`/`PlanTaskNode`（关键词配置化）、`ParallelInvokeNode`（删除漂移的第三份 resolveIntent）、`SupervisorGovernanceService`/`SupervisorGovernanceView`（词表来源可观测）。
  - `DistributedAgentProperties` 新增 `catalog.branch-capacity`、`catalog.cold-start-fallback-domains`、`catalog.default-intents`、`catalog.hint-rewrites`、`catalog.rule-routing.*` 与 `planning.max-parallel-domains` 配置项。
- **配置**：`nacos/agent-service.yaml`（及本地 profile）新增上述配置段，种子值保持与现状行为一致。
- **文档**：新增 `docs/supervisor-routing-roadmap.md`。
- **兼容性**：默认配置下行为与现状完全一致（同样 7 域、同样 intent、同样单次扇出上限 7）；compare-agent 在默认配置下即可被 LLM 规划并走通 single_agent 路径，槽位化后可进主图并行。
- **运行约束**：单次并行扇出上限（运行期）与槽位容量（编译期）分离，启动时校验 `max-parallel-domains ≤ branch-capacity`，配错即失败。
