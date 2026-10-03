# Spec Delta

## MODIFIED Requirements

### Requirement: 冷启动兜底词表

系统 SHALL 支持 `agent.distributed.catalog.cold-start-fallback-domains` 作为首次发现非空注册表前的诊断兼容词表，并明确标注来源；首次非空发现后动态词表完全接管。任何冷启动词表项 MUST NOT 生成不存在的可调用 Agent，正常请求的模型能力目录 MUST 只包含真实有效能力。空注册表 SHALL 告警但不得以词表伪造调用目标。

#### Scenario: 启动早期使用兜底词表
- **WHEN** 首次非空发现前收到目录诊断查询
- **THEN** 显示兼容词表及 COLD_START_FALLBACK 来源，但不存在相应实例的 Agent 不出现在可调用能力中

#### Scenario: 首次刷新成功后兜底失效
- **WHEN** 注册表首次返回至少一个 Agent
- **THEN** 后续领域来自注册表，未声明领域不再由兜底合并

#### Scenario: 注册表为空时保持兜底并告警
- **WHEN** 首次非空发现前注册表为空
- **THEN** 保留诊断词表并告警，模型只能使用其他真实可用工具或明确返回能力不足

### Requirement: LLM 规划 prompt 动态注入领域词表

正常请求的模型可见能力 SHALL 根据已授权动态目录提供真实 A2A 工具的 description、schema、领域及完整技能信息，并包括可用 MCP/本地工具。目录 MUST 保持稳定排序与同一模型轮次的快照一致性；系统 MUST NOT 先以领域关键词或固定领域名单替模型预选业务对象。新领域可直接被模型作为能力选用。

#### Scenario: 新域出现在规划 prompt 中
- **WHEN** compare Agent 已发现并授权可见
- **THEN** 模型能够看到并调用其能力，无需单独开启旧 JSON 领域规划器

#### Scenario: 词表快照一致性
- **WHEN** 同一模型轮次引用能力描述和执行目标映射
- **THEN** 使用同一版本快照，实际执行前再次校验目标仍有效

### Requirement: 计划校验白名单派生自领域目录

模型选择或 skill 声明的 Agent/工具 SHALL 按真实动态能力身份与权限校验；领域词表仅供描述与领域合法性辅助校验，MUST NOT 强制正常请求先产出 domain/intent/workflowType。并行限制 SHALL 同时约束 A2A/MCP/本地调用；技能或兼容计划声明领域时，词表外领域 MUST 被拒绝。保留旧任务的计划校验和容量配置兼容，不向新模式强制套用旧业务 workflowType。

#### Scenario: 已注册域的计划通过校验
- **WHEN** 技能步骤引用已发现且授权的 compare 能力
- **THEN** 通过能力校验，调用不依赖 compare 关键词

#### Scenario: 未注册域的计划被拒绝
- **WHEN** 技能步骤只引用不存在 Agent 的领域
- **THEN** 明确拒绝目标，不选择默认 listing Agent

#### Scenario: 新域的审批型 workflowType 通过校验
- **WHEN** 恢复旧任务的 compare_with_approval 计划且 compare 仍有效
- **THEN** 旧执行版本保留原校验语义；新模式以实际动作的审批约束执行

#### Scenario: 扇出上限配置错误时启动失败
- **WHEN** 使用旧槽位 runner 的并行限额超过槽位容量
- **THEN** 启动拒绝该 runner 配置，新模式同样校验自身执行容量

### Requirement: 并行分支槽位化路由

并行基础执行能力 SHALL 复用通用分支容量与结果聚合，并由模型发出的独立调用或显式 skill 的步骤依赖决定并行成员；系统 MUST NOT 根据入口关键词自动生成固定 listing+trade 组合。没有分配调用的分支 MUST NOT 执行；超出配置容量的调用 MUST 被拒绝或按已声明调度策略排队。

#### Scenario: 新域可进入并行执行
- **WHEN** 模型或显式技能声明独立的 listing 和 compare 调用，均有效且容量允许
- **THEN** 两项能力可并行执行并分别关联调用结果，无需领域专用图节点

#### Scenario: 空槽位不执行
- **WHEN** 运行期只分配两项调用
- **THEN** 其他分支不产生任何调用或副作用

#### Scenario: 超出运行期扇出上限被拒绝
- **WHEN** 单次并行调用超出配置限额且没有明确排队策略
- **THEN** 返回容量错误，不产生超限并发

### Requirement: 默认 intent 统一解析

系统 SHALL 保留按元数据、配置、Card 技能解析默认 intent 的兼容能力，供旧任务及明确需要缺省 intent 的协议适配使用。正常请求 MUST 以模型实际选择的任务/能力为依据，不得通过默认 intent 隐式选择业务动作；显式 skill 的 intent/目标以技能步骤声明为准。代码 MUST NOT 新增领域硬编码 intent switch。

#### Scenario: 配置种子保证现有域行为不变
- **WHEN** 旧执行版本恢复既有七域任务
- **THEN** 默认 intent 的兼容解析保持原行为，新 AUTO 请求不受该种子表硬路由

#### Scenario: 新域从 Agent Card 技能派生 intent
- **WHEN** 协议适配确需缺省 intent 且 Agent 发布了 compare.listings
- **THEN** 可解析该缺省值，但它不会触发额外 Agent 或工具调用

### Requirement: nextHint 通用 handoff 规则

系统 SHALL 保留 nextHints 和历史改写规则用于建议解释及旧任务恢复；在新 AUTO 模式中建议 MUST 作为工具结果交给模型，不能自动 handoff。显式 skill 模式中的后续步骤 MUST 由技能声明决定，nextHint 不能改变固定 target/依赖。

#### Scenario: 已知域 hint 触发 handoff
- **WHEN** 新 AUTO 执行返回 notification.send
- **THEN** 模型获得建议，平台不会在无后续有效调用时发送通知

#### Scenario: 改写表承接历史特例
- **WHEN** 旧任务恢复或模型解释收到 settlement.batch
- **THEN** 可以按配置解释为 settlement.prepare；新模式仍不自动执行

#### Scenario: 未知域 hint 被忽略
- **WHEN** hint 前缀不在当前有效目录
- **THEN** 该建议不得被作为有效调用目标

## REMOVED Requirements

### Requirement: 规则路由词表配置化

**Reason**: 用户明确要求正常请求由 LLM 根据工具描述选择能力，固定调用规则属于显式指定的 skill；入口关键词分流和默认领域兜底偏离该边界。

**Migration**: 新请求停用 rule-routing 的业务选择；需要固定流程的请求改为显式 workflow skill。旧关键词配置仅供旧 run 的兼容恢复，不作为新 AUTO 的失败兜底。主规格 Purpose 在实施/同步时更新为能力目录描述与校验来源。
