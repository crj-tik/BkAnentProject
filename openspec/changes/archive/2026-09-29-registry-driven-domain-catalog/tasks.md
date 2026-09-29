# Tasks: registry-driven-domain-catalog

## 1. 配置与领域目录基础

- [x] 1.1 `DistributedAgentProperties` 新增配置项：`catalog.branch-capacity`（默认 16）、`catalog.cold-start-fallback-domains`（默认现有 7 域）、`catalog.default-intents`（种子为现有 7 域当前 intent）、`catalog.hint-rewrites`（种子 `settlement.batch → settlement.prepare`）、`catalog.rule-routing.{default-domain,keywords,parallel-rules}`、`planning.max-parallel-domains`（默认 7）；Javadoc 与注释显式声明 `cold-start-fallback-domains` 为冷启动专用、首次注册表刷新成功后失效
- [x] 1.2 启动时校验 `planning.max-parallel-domains ≤ catalog.branch-capacity`，不满足即启动失败并给出明确错误信息
- [x] 1.3 新增 `com.bkanent.agent.catalog.DomainCatalog` 接口：`domains()`（字典序稳定）、`contains(String)`、`resolveDefaultIntent(String)`、`cards()`、`snapshot()`（含 `vocabularySource`）
- [x] 1.4 实现 `DomainCatalog`：包装 `AgentRegistry`，复用其 30s 节流刷新；实现冷启动状态机（`COLD_START_FALLBACK` → 首次成功刷新且非空 → `REGISTRY`，兜底此后不再合并；刷新成功但注册表为空则保持兜底并 WARN）
- [x] 1.5 实现 `resolveDefaultIntent` 三级回退：注册元数据 `agent-default-intent` → 配置 `default-intents` → Agent Card `supportedSkills[0]`；均无则返回 null
- [x] 1.6 单元测试：catalog 聚合、排序稳定、冷启动状态机各分支（未刷新/刷新成功非空/刷新成功为空）、intent 三级回退优先级

## 2. 规划与校验层动态化（Phase 1）

- [x] 2.1 `SupervisorIntentPlanningService.systemPrompt()` 改为按 catalog 快照动态生成：固定骨架 + 每域一行（domain、Agent 描述、skills），字典序稳定；workflowType 词表更新为结构型枚举 + `<domain>_with_approval` 派生说明；单次 `tryPlan` 内复用同一快照
- [x] 2.2 `WorkflowPlanValidator`：`ALLOWED_DOMAINS` 静态集合改为 `catalog.contains` 实时校验；`MAX_PARALLEL_DOMAINS` 改为读 `planning.max-parallel-domains`；`*_with_approval` 改为派生规则（前缀在词表即合法）；结构型 workflowType 保持静态枚举
- [x] 2.3 收编三份 `resolveIntent` switch：删除 `OfficialSupervisorGraphFactory.resolveIntent`、`ParseIntentNode.resolveIntent`、`ParallelInvokeNode.resolveIntent`，统一改调 `catalog.resolveDefaultIntent`
- [x] 2.4 `OfficialSupervisorGraphFactory.mapNextHint` 改为通用规则：`hint-rewrites` 改写 → `.` 前缀切 domain → `catalog.contains` 校验 → 生成 HandoffTarget；未命中词表的 hint 忽略
- [x] 2.5 核对现有 7 域 Agent 的 Agent Card `supportedSkills` 与现用 intent 命名对齐情况，不一致的域在 Nacos 元数据用 `agent-default-intent` 显式钉住（记录核对结果）
- [x] 2.6 单元/集成测试：prompt 含动态词表与描述、validator 放行词表内新域并拒绝词表外域、`_with_approval` 派生规则、hint 通用规则与改写表、兜底词表生效时行为与原硬编码等价
- [x] 2.7 Phase 1 验收：默认配置回归 7 域行为一致；`planning.llm-enabled=true` 时 LLM 能规划 `domain=compare`，通过校验并走通 single_agent 路径

## 3. 并行图槽位化（Phase 2）

- [x] 3.1 `OfficialSupervisorGraphNodeNames`：7 个 `PARALLEL_<DOMAIN>` 常量替换为按 `branch-capacity` 机械生成的槽位命名方法 `parallelBranchSlot(int index)`（`parallel_branch_0..N-1`）
- [x] 3.2 `OfficialSupervisorGraphFactory` 槽位化：删除 `PARALLEL_DOMAIN_NODES`；槽位名单静态生成；`addNode` 循环注册槽位节点；fan-in 聚合边对槽位名单机械生成；`addParallelConditionalEdges` mapping 改为槽位恒等映射
- [x] 3.3 `parallelTargets()` 改为 domain[i] → slot[i] 映射，校验改为 `catalog.contains`；`validParallelDomains`、`routeTarget`、`resolveHandoffTarget` 中的 `PARALLEL_DOMAIN_NODES.containsKey` 全部改为 catalog 校验
- [x] 3.4 `parallelBranch(int slotIndex)` 从 state 的 `PARALLEL_DOMAINS[slotIndex]` 读取本槽位 domain，调用现有通用 `parallelInvokeNode.invokeDomain`；槽位下标越界或 domain 缺失时返回失败分支结果
- [x] 3.5 `RouteDecisionNode` 加固：消费灰度 `routeOverrideDomains` 目标前校验 `catalog.contains`，非法值忽略并 WARN
- [x] 3.6 单元/集成测试：`[listing, compare]` 并行扇出/聚合、空槽位零执行（无分支结果/checkpoint 记录）、词表外 domain 判非法、审批中断-恢复的 checkpoint 兼容
- [x] 3.7 Phase 2 验收：compare 进入主图并行执行；更新 Supervisor LangGraph 流程图为槽位化拓扑

## 4. 规则路由配置化与治理可观测（Phase 3）

- [x] 4.1 `ParseIntentNode` 关键词表移到 `rule-routing.keywords` 配置（现有关键词做种子）；命中前校验 domain 为当前词表成员；未命中回退 `rule-routing.default-domain`
- [x] 4.2 `PlanTaskNode` 的 listing+trade 并行规则改为配置化的并行规则条目（关键词组 → 并行域列表），种子为现有规则
- [x] 4.3 `SupervisorGovernanceView`/`SupervisorGovernanceService` 暴露当前领域词表快照与 `vocabularySource`（`COLD_START_FALLBACK`/`REGISTRY`）
- [x] 4.4 启动日志输出词表来源与领域列表；词表来源切换时输出 INFO 日志
- [x] 4.5 新增 `docs/supervisor-routing-roadmap.md`：首条记录既定方向"规则路由整体降级为 LLM 规划失败时的兜底、关键词只保留高置信场景"，并预留后续迭代方向章节
- [x] 4.6 单元/集成测试：关键词配置生效与词表成员校验、默认域回退、治理视图词表来源字段、并行规则配置化
- [x] 4.7 Phase 3 验收：配置为 `compare` 域添加关键词后规则路由可命中；治理视图正确展示 `REGISTRY` 来源

## 5. 配置下发与全量回归

- [x] 5.1 `nacos/agent-service.yaml` 与本地 profile 增加本变更全部配置段（种子值与现状等价）
- [x] 5.2 全量回归：`mvn -gs .mvn-settings.xml -s .mvn-settings.xml compile` 及 agent-service 测试通过；默认配置下 7 域端到端行为与改造前一致
