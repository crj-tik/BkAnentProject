# Spec Delta

## MODIFIED Requirements

### Requirement: 领域词表从注册表派生

系统 SHALL 提供来自已注册 Agent supportedDomains 并集、按字典序稳定排序的领域目录，用于能力描述、发现及合法性辅助校验。各处 MUST 使用该共享目录，不能维护独立静态领域名单；新请求 MUST NOT 用领域词表或关键词代替模型选择实际能力。

#### Scenario: 新 Agent 注册后词表自动包含新域
- **WHEN** 声明 supportedDomains=[compare] 的 Agent 在 Nacos 完成注册且注册表刷新成功
- **THEN** 领域目录包含 compare，其有效授权能力可以被后续模型选择，无需修改代码

#### Scenario: Agent 注销后词表移除对应域
- **WHEN** 声明某领域的所有 Agent 均从注册表消失且刷新成功
- **THEN** 动态目录不再包含该领域；诊断兜底项不能保留为可调用 Agent

### Requirement: LLM 规划 prompt 动态注入领域词表

AUTO 与 EXPLICIT_SKILL 的模型可见能力 SHALL 根据已授权动态目录提供真实 A2A 工具的 description、schema、领域及发布技能信息，并包含有效 MCP/本地工具。目录 MUST 稳定排序且单轮快照一致；系统 MUST NOT 先以领域关键词或固定领域名单预选业务对象。显式技能可进一步限定有效能力集合，但不能跳过模型理解。

#### Scenario: 新域出现在规划 prompt 中
- **WHEN** compare Agent 已发现、授权可见且在本次有效范围内
- **THEN** 模型能够看到并调用该能力，无需开启旧 JSON 领域规划器或新增领域节点

#### Scenario: 词表快照一致性
- **WHEN** 同一模型轮次引用能力描述和执行目标映射
- **THEN** 使用同一版本快照，实际执行前再次验证目标有效性与权限

### Requirement: 计划校验白名单派生自领域目录

模型提出的调用及技能能力范围 SHALL 按真实动态能力身份、参数、权限和请求选项校验，领域词表只供描述及合法性辅助。新模式 MUST NOT 强制先产出 domain/intent/workflowType，也 MUST NOT 将校验扩展为技能业务顺序、依赖或完成检查。并行限制 SHALL 约束 A2A/MCP/本地调用；历史旧计划只供查询，不恢复执行。

#### Scenario: 已注册域的计划通过校验
- **WHEN** 模型提出已发现、授权且在有效技能范围内的 compare 调用
- **THEN** 调用可通过能力校验，不依赖 compare 关键词或领域 JSON 计划

#### Scenario: 未注册域的计划被拒绝
- **WHEN** 模型调用或技能允许清单引用不存在的 Agent 能力
- **THEN** 明确拒绝目标，不选择默认 listing Agent

#### Scenario: 新域的审批型 workflowType 通过校验
- **WHEN** 历史 checkpoint 含 compare_with_approval 计划且 compare 仍有效
- **THEN** 计划仅作为历史状态供查询；新模式以实际调用的审批约束执行，不生成业务 workflowType

#### Scenario: 扇出上限配置错误时启动失败
- **WHEN** 旧配置仍包含 max-parallel-domains 或 branch-capacity
- **THEN** 新 runner 不读取这些旧领域计划配置，并按自身模型工具调用容量限制执行

### Requirement: 并行分支槽位化路由

新模式的并行 SHALL 使用通用容量与结果聚合，由模型同轮提出的独立有效调用决定成员。系统 MUST NOT 根据入口关键词、技能正文步骤依赖或固定领域组合生成并行计划，不为 Agent 或技能增加专用业务分支。未分配调用的分支 MUST NOT 执行；超出容量 MUST 拒绝或按明确调度策略排队。历史旧任务不通过槽位 runner 恢复。

#### Scenario: 新域可进入并行执行
- **WHEN** 模型同轮提出 listing 和 compare 两项独立有效调用且容量允许
- **THEN** 两项能力可以并行执行并分别关联结果，无需领域专用图节点

#### Scenario: 空槽位不执行
- **WHEN** 运行期只分配两项调用
- **THEN** 其他容量槽位不产生调用、副作用或空业务执行记录

#### Scenario: 超出运行期扇出上限被拒绝
- **WHEN** 同轮调用数超过配置限额且没有明确排队策略
- **THEN** 返回容量错误，不产生超限并发

### Requirement: 默认 intent 统一解析

系统 MUST NOT 通过 Agent 元数据、配置或 Card 技能解析默认 intent 来选择新请求的业务动作。新模式 MUST 以模型实际提出的调用为依据，不能通过默认 intent 或技能名称隐式选择业务动作。代码 MUST NOT 新增领域硬编码 intent switch。

#### Scenario: 旧 intent 只供历史查询
- **WHEN** 旧 checkpoint 含既有域的 intent
- **THEN** 新执行路径不依据该值选择业务目标，历史查询保留原始字段

#### Scenario: 无默认 intent 路由
- **WHEN** 请求未指定 skill 且目录中 Agent Card 发布了 compare.listings
- **THEN** 仅由模型依据真实能力描述决定是否调用，不派生默认业务动作

### Requirement: nextHint 通用 handoff 规则

系统 SHALL 将 nextHints 作为结果信息提供给模型或历史查询。建议 MUST NOT 自动 handoff 或覆盖有效技能范围；只有模型后续提出并通过治理的实际调用才能执行下游动作。系统 MUST NOT 通过 nextHint 自动推进技能正文下一过程。

#### Scenario: 已知域 hint 交回模型
- **WHEN** 新模式执行返回 notification.send
- **THEN** 模型获得建议，平台在没有后续有效调用时不发送通知

#### Scenario: 历史 hint 不恢复调用
- **WHEN** 历史状态含 settlement.batch hint
- **THEN** 只读查询展示原始 hint，不通过旧改写规则触发任何 Agent 或工具调用

#### Scenario: 未知域 hint 被忽略
- **WHEN** hint 前缀不在当前有效目录
- **THEN** 不作为有效目标触发调用，可以作为不可执行建议供模型解释

## REMOVED Requirements

### Requirement: 冷启动兜底词表

**Reason**: 诊断 fallback 与旧路由配置一同退役，动态目录只应呈现真实注册能力，不应有与 Agent 清单分离的领域词表。

**Migration**: 空 registry 时目录为空并告警；使用 Nacos 注册与发现状态排查，不配置冷启动业务领域。

### Requirement: 规则路由词表配置化

**Reason**: 用户要求模型根据真实工具描述选择能力；指定技能后的过程来自正文指引，也仍由模型理解和提出调用。入口关键词、默认领域和模型失败后的关键词兜底偏离该边界。

**Migration**: 新请求使用真实能力 description/schema 与统一模型工具循环；跨服务过程指引通过 Markdown skill 提供，不增加 workflow 或 DAG。
