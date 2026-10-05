# Supervisor 路由与领域目录迭代路线图

> 本文档是 Supervisor 领域路由体系的长期迭代路线记录。所有已确定的未来方向与候选优化都记录在此，
> 后续可迭代方向持续追加到本文件。配套规格见 `openspec/specs/supervisor-domain-catalog/spec.md`
> （`registry-driven-domain-catalog` 变更归档后生效）。

## 2026-10-05 当前实现：模型遵循技能正文

用户最终选择第一种方案：正常请求给 LLM 配上真实 A2A/MCP 等工具的 description/schema，由模型判断调用；本次显式指定 skill 时，平台首轮前校验、固定版本并加载全文和能力范围，模型仍理解原始请求、提取参数、处理缺失与冲突，按正文指引逐轮选择工具。AUTO 与 EXPLICIT_SKILL 使用同一通用 Graph，不引入 workflow/DAG、正文步骤调度或业务顺序/完成检查器。

实现见 [realign-supervisor-tool-and-skill-orchestration](../openspec/changes/archive/2026-10-05-realign-supervisor-tool-and-skill-orchestration/design.md)，关联 LR-17/18、KI-17/18/19。2026-10-03 的 instruction/workflow 双执行器及结构化 DAG 版本已被本版取代。以下方向 1 的“LLM 失败后规则兜底”及方向 2/3 的入口规则扩展仅作历史记录。入口关键词、默认 Agent、自动 nextHint 交接和模型失败时关键词兜底已从执行路径移除。旧 Graph runner 已删除，历史 checkpoint 仅用于只读查询。本机独立验收库 7 条旧流程数据均已终态；该结果不代表生产排空。清理记录见 [旧执行链清理记录](supervisor-legacy-cleanup.md)。

目录发现、权限、审批、通用并行容量和恢复仍保留为基础设施；Graph 的统一工具执行节点持有实际业务调用权，模型节点只提出调用。平台严格约束能力范围、参数、身份、权限、预算与审批；正文流程的顺序和完成质量由模型遵循，不承诺程序强保证（KI-19）。Subagent 需要同步显式技能契约并复用已有 ReAct，旧 hint 与访谈运行面边界保持独立。

## 背景

`registry-driven-domain-catalog` 变更之后，Supervisor 的领域词表从 `DomainCatalog`
（Agent 注册表动态派生）统一供给：LLM 规划 prompt、计划校验白名单、并行图槽位路由、
规则路由关键词表。图拓扑与领域词表解耦（通用槽位），新增 Agent 只需注册到 Nacos。

## 历史方向与基础设施候选

### 1. 规则路由整体降级为 LLM 规划失败的兜底（历史，已替代）

**状态**：2026-10-04 已被最终方案替代，以下保留历史意图，不再作为实施方向。

当前规则路由（`ParseIntentNode` 关键词表 + `PlanTaskNode` 并行规则）是**加速层**：
在 `planning.strategy=rule-first`（默认）或 LLM 规划关闭时承担全部路由。未来将调整为：

- LLM 规划为主路径：所有请求先走 LLM 规划（`planning.llm-enabled=true` + 非 rule-first 策略）；
- 规则路由仅作为 LLM 规划失败/超时/不可用时的兜底；
- 关键词表只保留高置信场景（明显的领域专有词），低置信的长尾交给 LLM 判断。

前置条件：LLM 规划的时延与稳定性达标（需要生产数据验证），`planning.llm-enabled` 全量开启。

### 2. 关键词表收敛与置信度分级（历史，已替代）

**状态**：2026-10-04 已被最终方案替代，以下保留历史候选，不再作为实施方向。

对 `catalog.rule-routing.keywords` 做一次清理：只保留"命中即基本不会错"的关键词，
移除模糊词（如"消息"既可能是通知也可能是其他意图）。可考虑为关键词增加置信度标记，
高置信词可直接路由，低置信词仅作为 LLM 规划的提示上下文。

### 3. `RouteDecisionNode` 业务路由策略配置化（历史，已替代）

**状态**：2026-10-04 已被最终方案替代，以下保留历史候选，不再作为实施方向。

`RouteDecisionNode` 中的 trade→contract handoff 是业务策略（decision=MANUAL_REVIEW/
RISK_ALERT/needsMoreDocuments → contract），且默认只在 listing+trade 并行场景生效。
若未来出现第二组类似的业务路由链，应将其抽象为配置化的路由策略规则，而不是在代码里
堆叠第二份 if-else。当前仅一组，保持代码实现（YAGNI）。

### 4. Agent Card 技能与 intent 命名的语义对齐（旧任务维护）

**状态**：观察中（实施期已人工核对存量 7 域，见变更任务 2.5 的核对记录）。

2026-10-04 边界：以下 defaultIntent 候选只涉及存量旧 runner 维护，不纳入新模型工具路径。新目录通过稳定 capabilityId、Card skill ID 与本地技能身份映射提供能力定义，不以 default intent 选择调用。

`resolveDefaultIntent` 的第三级回退取 Agent Card `supportedSkills` 首项。若未来 A2A
生态的 skill 命名出现多语言/多风格混用，考虑在 Agent Card 层面引入显式的
`defaultIntent` 字段（进 A2A 协议扩展），或要求所有 Agent 通过注册元数据
`agent-default-intent` 显式声明，彻底移除对 skills[0] 的隐式依赖。

### 5. 领域词表变更事件化

**状态**：候选。

当前 `DomainCatalog` 在每次读取时惰性感知注册表变化（30s 节流刷新）。若未来需要
更及时的词表生效或审计能力，可将注册表变更（服务上线/下线）转化为事件，驱动：
词表快照主动刷新、prompt 缓存失效、治理端点变更通知（如 webhook 到运维群）。

### 6. 槽位容量的弹性观察（旧 runner 参数）

**状态**：观察中。

`branch-capacity` 默认 16。若并行扇出需求长期低于 8 或接近 16，相应调整默认值；
若需要超过 32，说明"单计划内多域并行"的模型本身需要重新评估（如分层 fan-out），
而不是继续放大槽位数。

2026-10-04 边界：branch-capacity 留给存量槽位图，不直接成为新 runner 的配置。新模型工具循环使用同轮独立调用的并发上限与总体预算，具体默认值测量后确定，不恢复领域计划槽位路由。

### 7. 遗留词表审计结论（2026-09-29 全量排查）

对槽位化改造后的代码做了一次全量硬编码排查，结论如下：

**已修复（活跃路径漂移点）：**
- `HandoffNode`：第四份 `resolveIntent` switch（handoff 到新域会错误兜底成 `listing.search`）已改为 `DomainCatalog.resolveDefaultIntent`；`resolveExpectedOutput` 的 default 分支从 "listing search summaries" 改为领域中立文案；顺带删除未被调用的 `containsListingIntent`。
- `LoadSessionNode`：系统约束标签搜索的领域集合（原 `Set.of(6 域)`，且漏了 media）改为 `DomainCatalog.domains()`，新域的约束标签自动可搜。

**已完成清理（以下名称仅用于历史审计）：**
- `WorkflowResumeSupport`（内含完整的第五份词表：关键词表 + intent switch + expectedOutput switch + mapNextHint switch + listing+trade 并行规则 + 默认 `"media"` 兜底）及其唯一消费者 `OfficialRouteGraphFactory`/`OfficialRouteGraphHolder`、`OfficialRegenerateGraphFactory`/`OfficialRegenerateGraphHolder`、`RouteDecisionSubgraph`、`RegenerateSubgraph`。这些是上一代 resume/route/regenerate 图架构的遗留，`execute()` 无任何 main/test 调用方。删除前需再确认无反射/条件装配依赖。

这些上一代 resume/route/regenerate 图架构及旧执行链已在 7.4 删除，并通过源码调用审计确认不再装配。

**评估后保留（属业务行为而非领域词表，新域优雅降级）：**
- `RouteDecisionNode`：trade→contract handoff 是业务路由策略（见方向 3）。
- `BuildNextAgentContextNode`：media/marketing/settlement 的下游上下文定制是业务行为，新域走通用上下文；若定制项增多再考虑元数据化。
- `SupervisorGovernanceService.resolveDomain`：灰度匹配的消息→域启发式（含 publish/copy/media/risk 等英文词），未接入 rule-routing 配置。漂移后果仅限"新域无法按消息文本自动命中灰度"，显式 userId/sessionId/domain 上下文灰度不受影响；改造收益低、行为变化风险高，暂保留。
- `SupervisorAgentRoutingService` 与旧图默认 Agent 选择策略：属于已删除的历史执行链；历史 selectedAgentId 仅在只读结果中展示，不触发调用。

## 迭代记录

- 2026-09-29：建立本文件；记录方向 1（规则路由降级为 LLM 兜底）为既定方向。
- 2026-09-29：完成槽位化改造后的全量硬编码审计，结论记入方向 7。
- 2026-10-03：形成 Supervisor 模型工具循环与显式 instruction/workflow 技能方案，仅文档，未实施。
- 2026-10-04：用户最终选择模型遵循技能正文；修订为 AUTO/EXPLICIT_SKILL 同一通用 Graph，取消 DAG 和业务步骤检查，明确顺序不作强保证。仅更新规划文档和认知清单，代码未调整。
- 2026-10-05：新入口、九 Subagent、共享显式范围与调用账本已实施；真实 Nacos/MySQL/HTTP A2A/MCP 混合协议及新 runner 恢复通过。按本机旧 run 只读核对完成旧执行链删除与规格同步；生产数据未检查。
