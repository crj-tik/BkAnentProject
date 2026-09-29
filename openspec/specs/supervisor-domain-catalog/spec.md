## Purpose

为 Supervisor 提供从 Agent 注册表派生的领域目录，使规划、校验、并行编排与规则路由共享同一份动态领域词表，新 Agent 注册后无需修改 Supervisor 代码即可被规划与调度。

## Requirements

### Requirement: 领域词表从注册表派生

系统 SHALL 提供一个领域目录（Domain Catalog），其领域词表 MUST 来源于 Agent 注册表中各 Agent 声明的 `supportedDomains` 的并集，并按字典序稳定排序。规划、校验、编排、规则路由各处 MUST NOT 再维护独立的静态领域名单。

#### Scenario: 新 Agent 注册后词表自动包含新域

- **WHEN** 一个声明了 `supportedDomains: [compare]` 的 Agent 在 Nacos 完成注册，且注册表刷新成功
- **THEN** 领域目录的领域词表包含 `compare`，无需重启或修改 Supervisor 代码

#### Scenario: Agent 注销后词表移除对应域

- **WHEN** 声明某领域的所有 Agent 均从注册表消失，且注册表刷新成功
- **THEN** 领域目录的领域词表不再包含该领域（除非该领域仍由冷启动兜底生效中提供）

### Requirement: 冷启动兜底词表

系统 SHALL 支持配置项 `agent.distributed.catalog.cold-start-fallback-domains` 作为冷启动兜底词表。该兜底 MUST 仅在注册表首次成功刷新完成之前生效；首次成功刷新后动态词表 MUST 完全接管，兜底词表 MUST NOT 再参与合并。若刷新成功但注册表为空，系统 MUST 继续保持兜底词表生效并输出 WARN 日志。

#### Scenario: 启动早期使用兜底词表

- **WHEN** Supervisor 启动后、注册表首次成功刷新之前收到规划请求
- **THEN** 领域目录返回冷启动兜底词表中的领域，规划与校验行为与硬编码时代一致

#### Scenario: 首次刷新成功后兜底失效

- **WHEN** 注册表首次成功刷新返回了至少一个 Agent
- **THEN** 后续规划与校验仅使用注册表派生词表，兜底词表中未被任何已注册 Agent 声明的领域不再生效

#### Scenario: 注册表为空时保持兜底并告警

- **WHEN** 注册表刷新成功但当前没有任何已注册 Agent
- **THEN** 领域目录继续返回兜底词表，并输出 WARN 级别日志提示词表来源异常

### Requirement: 词表来源可观测

系统 SHALL 在治理视图中暴露当前领域词表快照及其来源标记，来源 MUST 为 `COLD_START_FALLBACK` 或 `REGISTRY` 之一。

#### Scenario: 治理视图展示词表来源

- **WHEN** 调用 Supervisor 治理视图
- **THEN** 响应包含当前生效的领域列表与 `vocabularySource` 字段，取值与当前实际词表来源一致

### Requirement: LLM 规划 prompt 动态注入领域词表

LLM 规划器的 system prompt SHALL 根据领域目录的当前快照动态生成，MUST 包含当前词表中每个领域的名称及该领域已注册 Agent 的描述与技能列表，且领域顺序 MUST 稳定（字典序）。prompt 中 MUST NOT 出现与目录不一致的静态领域名单。

#### Scenario: 新域出现在规划 prompt 中

- **WHEN** `compare` 域已进入领域目录且 LLM 规划开启
- **THEN** 生成的 system prompt 包含 `compare` 及其 Agent 描述，LLM 可以合法规划出 `domain=compare`

#### Scenario: 词表快照一致性

- **WHEN** 同一次规划请求内多次引用领域词表
- **THEN** 各处使用的是同一份快照，不存在一次请求内词表不一致

### Requirement: 计划校验白名单派生自领域目录

计划校验器 SHALL 以领域目录的当前词表作为领域合法性判据，拒绝词表外的领域。单次计划的并行领域数量上限 SHALL 由配置 `agent.distributed.planning.max-parallel-domains` 控制（默认 7），且启动时 MUST 校验该值不大于 `agent.distributed.catalog.branch-capacity`，不满足时启动 MUST 失败。`workflowType` 中形如 `<domain>_with_approval` 的取值 SHALL 按派生规则判定：前缀在词表中即合法，不再依赖静态枚举。

#### Scenario: 已注册域的计划通过校验

- **WHEN** LLM 规划出 `domain=compare` 且 `compare` 在当前词表中
- **THEN** 计划校验通过

#### Scenario: 未注册域的计划被拒绝

- **WHEN** LLM 规划出的领域不在当前词表中
- **THEN** 校验抛出领域非法错误，计划被拒绝

#### Scenario: 新域的审批型 workflowType 通过校验

- **WHEN** 计划携带 `workflowType=compare_with_approval` 且 `compare` 在当前词表中
- **THEN** 校验通过，且仍要求 `requireApproval=true`

#### Scenario: 扇出上限配置错误时启动失败

- **WHEN** `max-parallel-domains` 配置值大于 `branch-capacity`
- **THEN** 服务启动失败并给出明确错误信息

### Requirement: 并行分支槽位化路由

并行图 SHALL 使用固定数量的通用分支槽位（默认 16，由 `agent.distributed.catalog.branch-capacity` 配置）替代按领域命名的分支节点。运行时 SHALL 按计划中领域列表的下标顺序将领域分配到槽位。未被分配的槽位 MUST NOT 执行任何逻辑。计划中的领域不在当前词表中时 SHALL 判定为非法计划。

#### Scenario: 新域可进入并行执行

- **WHEN** 计划包含并行领域 `[listing, compare]` 且两者均在当前词表中
- **THEN** 两个领域分别被分配到槽位 0 与槽位 1 并行执行，聚合并按领域归并结果

#### Scenario: 空槽位不执行

- **WHEN** 计划只包含 2 个并行领域而槽位容量为 16
- **THEN** 仅 2 个槽位节点被执行，其余 14 个槽位不产生任何执行记录、A2A 调用或 checkpoint 数据

#### Scenario: 超出运行期扇出上限被拒绝

- **WHEN** 计划的并行领域数量超过 `max-parallel-domains`
- **THEN** 校验拒绝该计划

### Requirement: 默认 intent 统一解析

系统 SHALL 提供统一的按域解析默认 intent 的能力，解析顺序 MUST 为：Agent 注册元数据 `agent-default-intent` → 配置 `agent.distributed.catalog.default-intents` → 该域 Agent Card 的 `supportedSkills` 首项。代码中 MUST NOT 再存在按领域硬编码的 intent switch。

#### Scenario: 配置种子保证现有域行为不变

- **WHEN** 解析现有 7 个领域的默认 intent
- **THEN** 结果与改造前完全一致（如 `marketing` → `marketing.generate_copy`）

#### Scenario: 新域从 Agent Card 技能派生 intent

- **WHEN** 解析 `compare` 域的默认 intent，且其 Agent Card 声明了技能 `compare.listings`
- **THEN** 默认 intent 为 `compare.listings`

### Requirement: nextHint 通用 handoff 规则

系统 SHALL 按通用规则处理 Agent 返回的 nextHint：先经配置 `agent.distributed.catalog.hint-rewrites` 改写，再按 `.` 前缀切出目标领域，领域在当前词表中即允许 handoff，intent 取改写后的 hint 本身。未命中词表的 hint SHALL 被忽略。

#### Scenario: 已知域 hint 触发 handoff

- **WHEN** Agent 返回 nextHint `notification.send` 且 `notification` 在当前词表中
- **THEN** 系统 handoff 到 `notification` 域，intent 为 `notification.send`

#### Scenario: 改写表承接历史特例

- **WHEN** Agent 返回 nextHint `settlement.batch`，且 `hint-rewrites` 配置了 `settlement.batch → settlement.prepare`
- **THEN** 系统 handoff 到 `settlement` 域，intent 为 `settlement.prepare`

#### Scenario: 未知域 hint 被忽略

- **WHEN** Agent 返回的 nextHint 前缀不在当前词表中
- **THEN** 该 hint 被忽略，不触发 handoff

### Requirement: 规则路由词表配置化

规则路由的领域关键词表 SHALL 由配置 `agent.distributed.catalog.rule-routing.keywords`（按领域分组）提供，默认配置 MUST 与现有关键词行为一致。未命中任何关键词时 SHALL 回退到配置的默认领域。规则路由命中的领域 MUST 为当前词表成员，非成员领域的关键词命中 SHALL 被忽略。

#### Scenario: 关键词命中决定领域

- **WHEN** 用户消息包含"合同"且 `contract` 域配置了该关键词
- **THEN** 规则路由判定领域为 `contract`

#### Scenario: 新增域关键词无需改代码

- **WHEN** 运维在配置中为 `compare` 域添加关键词"对比"
- **THEN** 包含"对比"的消息被规则路由到 `compare` 域，无需修改或重启代码逻辑（配置刷新生效）

#### Scenario: 未命中时回退默认领域

- **WHEN** 用户消息未命中任何领域的关键词
- **THEN** 规则路由回退到配置的默认领域
