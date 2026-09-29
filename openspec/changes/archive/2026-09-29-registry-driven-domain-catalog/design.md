# Design: registry-driven-domain-catalog

## Context

当前状态（已核实）：

- 领域词表有四处独立静态副本：LLM 规划 prompt（`SupervisorIntentPlanningService.systemPrompt`）、校验白名单（`WorkflowPlanValidator.ALLOWED_DOMAINS`，`MAX_PARALLEL_DOMAINS=7` 恰好等于域名数）、并行图拓扑（`OfficialSupervisorGraphFactory.PARALLEL_DOMAIN_NODES` 7 个命名分支）、规则路由关键词表（`ParseIntentNode`/`PlanTaskNode`）。
- 默认 intent 的 switch 有三份副本：`OfficialSupervisorGraphFactory.resolveIntent`、`ParseIntentNode.resolveIntent`、`ParallelInvokeNode.resolveIntent`——第三份已漂移（缺 `notification`/`settlement`，落 default 变成 `listing.search`）。
- 动态侧已经就绪：`DynamicAgentRegistry` 从 Nacos 元数据 `agent-domains` + Agent Card `supportedDomains` 聚合，`listCards()`/`findByDomain()` 可用，30s 节流懒刷新；`ParallelInvokeNode.invokeDomain(request, state, domain)` 与 `SupervisorAgentRoutingService.selectAgent(domain, ...)` 本身完全通用，无领域硬编码。
- LangGraph `StateGraph` 是编译期模型：`addParallelConditionalEdges` 需要静态 edge mapping，`OfficialSupervisorGraphHolder` 启动时一次性构建 `CompiledGraph`。
- 已有配置骨架：`agent.distributed.catalog.strict-nacos`、`agent.distributed.planning.{llm-enabled,strategy,allow-fallback}`、灰度 `routeOverrideDomains`。

约束：默认配置下行为必须与现状完全一致；不支持运行时改变图拓扑（LangGraph 限制），也不为此引入图重建。

## Goals / Non-Goals

**Goals:**

- 单一派生点：`DomainCatalog` 从 `AgentRegistry` 派生领域词表/默认 intent/Agent 描述，四层消费方统一取数。
- 图拓扑与领域词表彻底解耦：新增域只要求注册表注册，图零改动、零重建。
- 两道资源闸门分离：编译期槽位容量（`branch-capacity`）与运行期单次扇出上限（`max-parallel-domains`）。
- 冷启动兜底语义显式化、可观测。

**Non-Goals:**

- 不改 A2A 协议、Agent Card 结构、`AgentRegistry` 接口。
- 不泛化 `RouteDecisionNode` 的业务路由策略（trade→contract 是业务规则不是词表；仅做灰度 override 目标的词表校验加固）。
- 不实现"规则路由降级为 LLM 兜底"——仅写入 `docs/supervisor-routing-roadmap.md` 作为既定方向。
- 不改 `workflowType` 结构型取值（`single_agent`/`parallel`/`marketing_pipeline` 保持静态枚举）。

## Decisions

### D1: DomainCatalog 作为唯一派生点

```
Nacos (agent-domains / Agent Card) ──▶ DynamicAgentRegistry ──▶ DomainCatalog
                                                                      │
        ┌───────────────┬───────────────┬───────────────┬───────────┤
        ▼               ▼               ▼               ▼           ▼
  LLM system      WorkflowPlan    槽位路由域校验    resolveDefault  规则路由
  prompt 注入      Validator                       Intent          关键词表
```

- 接口：`Set<String> domains()`（字典序稳定）、`boolean contains(String)`、`String resolveDefaultIntent(String)`、`List<AgentCard> cards()`、`CatalogSnapshot snapshot()`（含 `vocabularySource`）。
- 实现包装 `AgentRegistry`，每次取数走注册表现有 30s 节流刷新，不新增定时器。
- 冷启动状态机：`COLD_START_FALLBACK`（启动后、首次成功刷新前）→ 首次 `refreshAllFromDiscovery()` 成功 → `REGISTRY`（兜底词表此后不再参与合并）。刷新成功但注册表为空：保持 `COLD_START_FALLBACK` + WARN。词表来源通过 `SupervisorGovernanceView` 暴露。

**备选**：各消费方直接查 `AgentRegistry`。否决——冷启动兜底、排序稳定、intent 回退链会散在四处重新漂移。

### D2: 并行图槽位化（而非图重建）

`PARALLEL_DOMAIN_NODES`（domain→nodeName）替换为静态槽位名单 `parallel_branch_0..N-1`（N=`branch-capacity`，默认 16）：

- 图构建：`for slot in slots: addNode(slot, parallelBranch(slotIndex))`；fan-in 聚合边对槽位名单机械生成；`addParallelConditionalEdges` 的 mapping 变为槽位名静态恒等映射。拓扑编译期固定，**永不需要重建**。
- 运行时绑定：`parallelTargets()` 校验 domains ⊆ catalog 后返回 `new MultiCommand(domains.stream().map(this::slotFor).toList(), updates)`——domain[i] → slot[i]，domain 列表本身已在 `PARALLEL_DOMAINS` state key 中。
- 槽位执行体：`parallelBranch(slotIndex)` 从 state 读 `PARALLEL_DOMAINS[slotIndex]` 得到本槽位 domain，调用通用的 `parallelInvokeNode.invokeDomain(...)`（零改动复用）。
- 空槽位成本：未被 MultiCommand 路由的槽位节点不执行——无 CPU、无 A2A 调用、无 checkpoint 数据。16 个槽位的全部开销是图定义对象的几 KB。真正的资源闸门是运行期 `max-parallel-domains`（每次计划的扇出数），与槽位容量是两道独立闸门，启动时校验 `max ≤ capacity`，配错即失败。
- 节点命名从 `parallel_listing` 变为 `parallel_branch_0`：checkpoint 兼容性安全——`interruptAfter(APPROVAL_GATE)` 在扇出之前，恢复时分支节点尚未执行，恢复后由新图的 slot 映射接管；state keys 全部不变。trace/流程图中 domain 语义由分支结果与 `handoff.started` 事件 metadata（本已携带 domain）承载。

**备选 A**：注册表变更时重建 `CompiledGraph`（holder 换 AtomicReference）。否决——在途 checkpoint 恢复论证复杂，重建窗口内行为不一致，且完全不必：槽位化后图里没有领域词汇。
**备选 B**：保持 7 个命名分支 + 每增一个域改一次图。即现状，漂移根源。

### D3: LLM prompt 动态注入

`systemPrompt()` 按 `catalog.snapshot()` 生成：固定骨架 + 动态段落（每域一行：`domain — agent 描述（skills: ...）`，字典序）。骨架中的 workflowType 词表同步更新为结构型静态枚举 + `<domain>_with_approval` 派生说明。同一次 `tryPlan` 内用同一份快照。兜底词表生效时 prompt 内容与原硬编码 7 域等价，行为兼容。

### D4: 校验派生化

- `ALLOWED_DOMAINS` → `catalog.contains(domain)`（每次校验实时查，新域即时放行，无需重启）。
- `MAX_PARALLEL_DOMAINS` → `planning.max-parallel-domains`（默认 7）。
- `<domain>_with_approval` → 派生规则：后缀匹配且前缀在词表即合法（顺带修复 `ParseIntentNode` 已能动态生成 `domain + "_with_approval"` 而静态白名单必然拒绝的既有失配）。
- 结构型 `workflowType`（`single_agent`/`parallel`/`marketing_pipeline`）保持静态枚举。

### D5: 默认 intent 三级回退，三份 switch 收编

`catalog.resolveDefaultIntent(domain)`：①注册元数据 `agent-default-intent`（新 Agent 显式声明）→ ②配置 `default-intents` 种子表（填入现有 7 域当前值，保证行为不变）→ ③Agent Card `supportedSkills[0]`（compare-agent 已声明 `compare.listings`，天然可用）→ 都没有则返回 null 由调用方报错。删除 `OfficialSupervisorGraphFactory`、`ParseIntentNode`、`ParallelInvokeNode` 三处 switch。

**备选**：统一用 `supportedSkills[0]`。否决——现有 7 域的 skill 声明与当前 intent 命名未逐一核实，贸然切换有行为变更风险；种子表保现状，新域走技能声明，显式元数据供精确控制。

### D6: nextHint 通用规则

`mapNextHint` 替换为：`hint → hint-rewrites 配置改写（种子：settlement.batch → settlement.prepare）→ 按 . 前缀切 domain → catalog.contains 校验 → HandoffTarget(domain, 改写后 hint)`。未命中词表的 hint 忽略。覆盖现有三条规则的语义，新域 hint 自动可用。`resolveHandoffTarget` 中 route_decision 路径的 `PARALLEL_DOMAIN_NODES.containsKey` 改为 `catalog.contains`。

### D7: 规则路由词表配置化（本期）+ 路线图文档（未来）

- `agent.distributed.catalog.rule-routing.keywords: Map<domain, List<String>>`，种子为现有关键词；命中校验 domain 必须是当前词表成员；`rule-routing.default-domain`（默认 `listing`）承接未命中回退；`PlanTaskNode` 的 listing+trade 并行规则改为配置化的并行规则条目。
- 新增 `docs/supervisor-routing-roadmap.md`：首条记录既定方向"规则路由整体降级为 LLM 规划失败时的兜底、关键词只保留高置信场景"，后续迭代方向持续追加。

### D8: 配置 Schema 汇总

```yaml
agent:
  distributed:
    planning:
      max-parallel-domains: 7        # 运行期单次扇出上限（原 MAX_PARALLEL_DOMAINS）
    catalog:
      branch-capacity: 16            # 编译期槽位容量，改后需重启
      cold-start-fallback-domains:   # 冷启动专用，首次注册表刷新成功后失效
        [listing, marketing, media, trade, contract, settlement, notification]
      default-intents:               # 现有域行为种子
        { marketing: marketing.generate_copy, media: media.generate_video_task,
          trade: trade.feasibility_analysis, contract: contract.risk_review,
          notification: notification.send, settlement: settlement.prepare,
          listing: listing.search }
      hint-rewrites: { settlement.batch: settlement.prepare }
      rule-routing:
        default-domain: listing
        keywords: { contract: [合同,签约,归档,ocr,contract], ... }  # 现有词迁移
```

## Risks / Trade-offs

- [LLM 对动态注入的新域规划质量不可控] → prompt 注入 description/skills 而非裸域名；`planning.llm-enabled` 默认 false 不变，按域灰度开启。
- [注册表抖动导致词表短时缩容，在途计划校验失败] → 校验只作用于新计划生成时点；在途执行不二次校验；注册表为空时保持兜底（见 D1）。
- [槽位化后 trace 节点名丢失 domain 语义] → 分支结果、`handoff.started/completed` 事件 metadata 均携带 domain；流程图按统一槽位命名收敛展示。
- [default-intents 种子表成为新的静态漂移点] → 种子表仅保现状兼容，文档注明新域必须走元数据/Agent Card 技能声明，禁止向种子表追加新域。
- [`branch-capacity` 增大后图节点膨胀] → 容量是纯拓扑概念无运行期成本；上限建议 ≤32，超出说明并行模型本身需要重新设计。
- [灰度 `routeOverrideDomains` 指向词表外领域] → 加固：`RouteDecisionNode` 消费 override 前校验 `catalog.contains`，非法值忽略并 WARN。

## Migration Plan

1. **配置先行**：下发含全部种子值的新配置（行为与现状等价），验证无回归。
2. **Phase 1（词表统一）**：DomainCatalog + prompt/validator/intent/hint 改造。默认配置下回归验证 7 域行为一致；开启 LLM 规划后验证 compare 可规划、过校验、走通 single_agent。
3. **Phase 2（图槽位化）**：一次部署完成图替换；验证 `[listing, compare]` 并行扇出/聚合；验证审批中断-恢复的 checkpoint 兼容（恢复点在扇出前，天然安全）。
4. **Phase 3（规则路由 + 治理）**：关键词配置化、治理视图词表来源、roadmap 文档落库。
5. **回滚**：每 Phase 独立可回滚；Phase 1/3 纯 service 层改动直接回滚代码；Phase 2 回滚即恢复命名分支图，无在途数据迁移（checkpoint state keys 未变）。

## Open Questions

- ~~各存量 Agent 的 Agent Card `supportedSkills` 与现用 intent 命名的对齐情况~~ 已核对（实施期）：listing/marketing/notification/settlement 的 skills[0] 与现用 intent 一致；media（card: `media.generate`）、trade（card: `trade.analyze`）、contract（card: `contract.review`）三者不一致，已在各自 Nacos 配置的 discovery metadata 中以 `agent-default-intent` 显式钉住现用值（`media.generate_video_task` / `trade.feasibility_analysis` / `contract.risk_review`），保证回退链第一级命中。
