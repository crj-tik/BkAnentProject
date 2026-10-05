## Purpose

为 Supervisor 提供来自真实 Agent 注册表的动态能力描述和合法性辅助目录，统一授权工具定义与实际执行目标；新请求由模型根据 A2A、MCP 与本地工具选择调用，领域词表不承担入口关键词路由，旧任务保留相容恢复。

## Requirements

### Requirement: 领域词表从注册表派生

系统 SHALL 提供来自已注册 Agent supportedDomains 并集、按字典序稳定排序的领域目录，用于能力描述、发现及合法性辅助校验。各处 MUST 使用该共享目录，不能维护独立静态领域名单；新请求 MUST NOT 用领域词表或关键词代替模型选择实际能力。

#### Scenario: 新 Agent 注册后词表自动包含新域
- **WHEN** 声明 supportedDomains=[compare] 的 Agent 在 Nacos 完成注册且注册表刷新成功
- **THEN** 领域目录包含 compare，其有效授权能力可以被后续模型选择，无需修改代码

#### Scenario: Agent 注销后词表移除对应域
- **WHEN** 声明某领域的所有 Agent 均从注册表消失且刷新成功
- **THEN** 动态目录不再包含该领域；诊断兜底项不能保留为可调用 Agent

### Requirement: 冷启动兜底词表

系统 SHALL 支持 agent.distributed.catalog.cold-start-fallback-domains 作为首次发现非空注册表前的诊断兼容词表并标注来源；首次非空发现后动态词表完全接管。冷启动词表项 MUST NOT 生成不存在的可调用 Agent。空注册表 SHALL 告警，但模型可调用目录 MUST 只包含真实有效能力。

#### Scenario: 启动早期使用兜底词表
- **WHEN** 首次非空发现前收到目录诊断查询
- **THEN** 显示兼容词表及 COLD_START_FALLBACK 来源，不存在相应实例的 Agent 不出现在可调用能力中

#### Scenario: 首次刷新成功后兜底失效
- **WHEN** 注册表首次返回至少一个 Agent
- **THEN** 后续领域来自注册表，未声明领域不再由兜底合并

#### Scenario: 注册表为空时保持兜底并告警
- **WHEN** 首次非空发现前注册表为空
- **THEN** 保留诊断词表并告警，模型只能使用其他真实能力或明确返回能力不足

### Requirement: 词表来源可观测

系统 SHALL 在治理视图中暴露当前领域词表快照及其来源标记，来源 MUST 为 `COLD_START_FALLBACK` 或 `REGISTRY` 之一。

#### Scenario: 治理视图展示词表来源

- **WHEN** 调用 Supervisor 治理视图
- **THEN** 响应包含当前生效的领域列表与 `vocabularySource` 字段，取值与当前实际词表来源一致

### Requirement: LLM 规划 prompt 动态注入领域词表

AUTO 与 EXPLICIT_SKILL 的模型可见能力 SHALL 根据已授权动态目录提供真实 A2A 工具的 description、schema、领域及发布技能信息，并包含有效 MCP/本地工具。目录 MUST 稳定排序且单轮快照一致；系统 MUST NOT 先以领域关键词或固定领域名单预选业务对象。显式技能可进一步限定有效能力集合，但不能跳过模型理解。

#### Scenario: 新域出现在规划 prompt 中
- **WHEN** compare Agent 已发现、授权可见且在本次有效范围内
- **THEN** 模型能够看到并调用该能力，无需开启旧 JSON 领域规划器或新增领域节点

#### Scenario: 词表快照一致性
- **WHEN** 同一模型轮次引用能力描述和执行目标映射
- **THEN** 使用同一版本快照，实际执行前再次验证目标有效性与权限

### Requirement: 计划校验白名单派生自领域目录

模型提出的调用及技能能力范围 SHALL 按真实动态能力身份、参数、权限和请求选项校验，领域词表只供描述及合法性辅助。新模式 MUST NOT 强制先产出 domain/intent/workflowType，也 MUST NOT 将校验扩展为技能业务顺序、依赖或完成检查。并行限制 SHALL 约束 A2A/MCP/本地调用；旧任务 SHALL 保留相容旧版本的领域计划和容量校验。

#### Scenario: 已注册域的计划通过校验
- **WHEN** 模型提出已发现、授权且在有效技能范围内的 compare 调用
- **THEN** 调用可通过能力校验，不依赖 compare 关键词或领域 JSON 计划

#### Scenario: 未注册域的计划被拒绝
- **WHEN** 模型调用或技能允许清单引用不存在的 Agent 能力
- **THEN** 明确拒绝目标，不选择默认 listing Agent

#### Scenario: 新域的审批型 workflowType 通过校验
- **WHEN** 恢复旧任务的 compare_with_approval 计划且 compare 仍有效
- **THEN** 旧执行版本保留原校验语义；新模式以实际调用的审批约束执行，不生成业务 workflowType

#### Scenario: 扇出上限配置错误时启动失败
- **WHEN** 使用旧槽位 runner 的 max-parallel-domains 大于 branch-capacity
- **THEN** 启动拒绝该 runner 配置，新模式同样校验自身执行容量，但不继承旧领域计划选择方式

### Requirement: 并行分支槽位化路由

新模式的并行 SHALL 使用通用容量与结果聚合，由模型同轮提出的独立有效调用决定成员。系统 MUST NOT 根据入口关键词、技能正文步骤依赖或固定领域组合生成并行计划，不为 Agent 或技能增加专用业务分支。未分配调用的分支 MUST NOT 执行；超出容量 MUST 拒绝或按明确调度策略排队。旧任务 SHALL 继续使用相容槽位 runner 恢复。

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

系统 SHALL 保留按 Agent 元数据、配置、Card 技能解析默认 intent 的兼容能力，供旧任务及明确需要缺省 intent 的协议适配使用。新模式 MUST 以模型实际提出的调用为依据，不能通过默认 intent 或技能名称隐式选择业务动作。代码 MUST NOT 新增领域硬编码 intent switch。

#### Scenario: 配置种子保证现有域行为不变
- **WHEN** 旧执行版本恢复既有七域任务
- **THEN** 默认 intent 兼容解析保持原行为，新 AUTO 和 EXPLICIT_SKILL 不受种子表硬路由

#### Scenario: 新域从 Agent Card 技能派生 intent
- **WHEN** 协议适配确需缺省 intent 且 Agent 发布了 compare.listings
- **THEN** 可以解析该缺省值，但不会触发额外 Agent 或工具调用

### Requirement: nextHint 通用 handoff 规则

系统 SHALL 保留 nextHints 与历史改写用于结果解释及旧任务恢复。在新 AUTO 和 EXPLICIT_SKILL 中，建议 MUST 返回模型，MUST NOT 自动 handoff 或覆盖有效技能范围；只有模型后续提出并通过治理的实际调用才能执行下游动作。系统 MUST NOT 通过 nextHint 自动推进技能正文下一过程。

#### Scenario: 已知域 hint 触发 handoff
- **WHEN** 新模式执行返回 notification.send
- **THEN** 模型获得建议，平台在没有后续有效调用时不发送通知；存量旧 runner 的恢复仍使用其相容 handoff 语义

#### Scenario: 改写表承接历史特例
- **WHEN** 旧任务恢复或模型解释收到 settlement.batch
- **THEN** 可以按配置解释为 settlement.prepare，新模式仍不自动执行

#### Scenario: 未知域 hint 被忽略
- **WHEN** hint 前缀不在当前有效目录
- **THEN** 不作为有效目标触发调用，可以作为不可执行建议供模型解释
