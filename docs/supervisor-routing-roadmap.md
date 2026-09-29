# Supervisor 路由与领域目录迭代路线图

> 本文档是 Supervisor 领域路由体系的长期迭代路线记录。所有已确定的未来方向与候选优化都记录在此，
> 后续可迭代方向持续追加到本文件。配套规格见 `openspec/specs/supervisor-domain-catalog/spec.md`
> （`registry-driven-domain-catalog` 变更归档后生效）。

## 背景

`registry-driven-domain-catalog` 变更之后，Supervisor 的领域词表从 `DomainCatalog`
（Agent 注册表动态派生）统一供给：LLM 规划 prompt、计划校验白名单、并行图槽位路由、
规则路由关键词表。图拓扑与领域词表解耦（通用槽位），新增 Agent 只需注册到 Nacos。

## 已确定的未来方向

### 1. 规则路由整体降级为 LLM 规划失败的兜底

**状态**：既定方向，未排期。

当前规则路由（`ParseIntentNode` 关键词表 + `PlanTaskNode` 并行规则）是**加速层**：
在 `planning.strategy=rule-first`（默认）或 LLM 规划关闭时承担全部路由。未来将调整为：

- LLM 规划为主路径：所有请求先走 LLM 规划（`planning.llm-enabled=true` + 非 rule-first 策略）；
- 规则路由仅作为 LLM 规划失败/超时/不可用时的兜底；
- 关键词表只保留高置信场景（明显的领域专有词），低置信的长尾交给 LLM 判断。

前置条件：LLM 规划的时延与稳定性达标（需要生产数据验证），`planning.llm-enabled` 全量开启。

### 2. 关键词表收敛与置信度分级

**状态**：候选，依赖方向 1。

对 `catalog.rule-routing.keywords` 做一次清理：只保留"命中即基本不会错"的关键词，
移除模糊词（如"消息"既可能是通知也可能是其他意图）。可考虑为关键词增加置信度标记，
高置信词可直接路由，低置信词仅作为 LLM 规划的提示上下文。

### 3. `RouteDecisionNode` 业务路由策略配置化

**状态**：候选。

`RouteDecisionNode` 中的 trade→contract handoff 是业务策略（decision=MANUAL_REVIEW/
RISK_ALERT/needsMoreDocuments → contract），且默认只在 listing+trade 并行场景生效。
若未来出现第二组类似的业务路由链，应将其抽象为配置化的路由策略规则，而不是在代码里
堆叠第二份 if-else。当前仅一组，保持代码实现（YAGNI）。

### 4. Agent Card 技能与 intent 命名的语义对齐

**状态**：观察中（实施期已人工核对存量 7 域，见变更任务 2.5 的核对记录）。

`resolveDefaultIntent` 的第三级回退取 Agent Card `supportedSkills` 首项。若未来 A2A
生态的 skill 命名出现多语言/多风格混用，考虑在 Agent Card 层面引入显式的
`defaultIntent` 字段（进 A2A 协议扩展），或要求所有 Agent 通过注册元数据
`agent-default-intent` 显式声明，彻底移除对 skills[0] 的隐式依赖。

### 5. 领域词表变更事件化

**状态**：候选。

当前 `DomainCatalog` 在每次读取时惰性感知注册表变化（30s 节流刷新）。若未来需要
更及时的词表生效或审计能力，可将注册表变更（服务上线/下线）转化为事件，驱动：
词表快照主动刷新、prompt 缓存失效、治理端点变更通知（如 webhook 到运维群）。

### 6. 槽位容量的弹性观察

**状态**：观察中。

`branch-capacity` 默认 16。若并行扇出需求长期低于 8 或接近 16，相应调整默认值；
若需要超过 32，说明"单计划内多域并行"的模型本身需要重新评估（如分层 fan-out），
而不是继续放大槽位数。

## 迭代记录

- 2026-09-29：建立本文件；记录方向 1（规则路由降级为 LLM 兜底）为既定方向。
