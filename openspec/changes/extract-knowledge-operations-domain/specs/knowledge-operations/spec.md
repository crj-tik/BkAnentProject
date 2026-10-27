# 经验知识运营契约

## Purpose

支持知识运营人员把获准访谈整理为可审核、发布、引用和撤回的经营案例与打法，向店长提供带来源、适用边界和版本的经营支持。将知识质量、应用证据和发布权限分别管理，保障个人记忆、访谈事实和运营知识的独立责任。

## ADDED Requirements

### Requirement: 经营知识拥有独立身份及结构化边界

系统 SHALL 为运营案例和打法分配独立业务身份及不可变 revision，记录经营问题、情境、前提、动作、来源所述结果、适用边界、未核实项和证据引用。来源资产 ID、旧报告 ID 与新知识 ID MUST 保持不同语义。

#### Scenario: 同一来源产生两个运营版本
- **WHEN** 同一逐字稿的打法被运营人员修订
- **THEN** 系统创建可追溯的新 revision 并保留前版及来源，不覆盖访谈逐字稿或原报告

### Requirement: 知识域与访谈域保持独立事实所有权

Knowledge service SHALL 独占新经营案例、打法、不可变 revision、审核决定、发布范围、知识来源依赖、生成任务和操作回执。访谈域继续独占原报告及报告任务、会话、话轮和逐字稿；reportId/assetId MUST NOT 被复用为 knowledgeId。旧 `playbook_card`/`commonality_report` 只盘点并保留在访谈域，不搬表、不复用其 ID 或固定置信度，不在本 change 迁移存量。v1 可以记录不透明 report reference，但 MUST NOT 读取报告正文或重放/覆盖其任务。

#### Scenario: 遇到访谈侧旧打法表
- **WHEN** 新知识模块盘点 `playbook_card` 或 `commonality_report`
- **THEN** 旧表及 ID 留在访谈域，知识域按新 ID 创建资产并保留来源映射清单

### Requirement: 原声与经营效果证据分别验证

原声引用 SHALL 对照精确来源快照核验；来源评级、案例成功叙述、模型质量分和重复样本数量 MUST NOT 单独成为已证实经营有效的依据。归纳 SHALL 保留出处、反例或缺失说明，并区分自述、已核验和未验证结果。

#### Scenario: 多个案例提到相同做法
- **WHEN** 两个或更多案例出现同一种做法但缺少独立效果证据
- **THEN** 系统可以形成有来源的候选归纳，不能仅按样本数量标记为已验证有效

### Requirement: 编辑审核与发布遵循当前权限和版本

知识 SHALL 提供草稿、待审核、已发布和已撤回状态，编辑创建新 revision，提交/审核/发布/撤回均核验对应的精确 Auth 操作和 expectedVersion，并记录实际操作者及决定。A2A 能力 `a2a:knowledge-agent` SHALL 仅映射精确操作 `agent.workflow.invoke.knowledge`；该 Agent invoke 权限 MUST NOT 代替知识创建、审核、发布、撤回、读取等领域操作权限，能力未登记时按当前 Agent 规则隐藏/拒绝。审核者分离要求 SHALL 由受管审核策略控制，模型生成完成不得替代人工发布决定。

#### Scenario: 两人并发修改或审核
- **WHEN** 一个请求基于旧 revision 提交审核或发布
- **THEN** 系统返回可辨识版本冲突，保留已接受的新修订和原回执，不覆盖后续内容

#### Scenario: Agent invoke 权限不能替代发布权限
- **WHEN** 调用者有 `agent.workflow.invoke.knowledge`，但当前 Auth context 不含适用于目标范围的知识发布操作
- **THEN** Agent 可以按能力策略被发现或调用，但发布动作被拒绝且不得改变知识状态

### Requirement: 操作回执和生成任务可可靠找回

生成、编辑及运营动作 SHALL 以稳定请求身份和内容 hash 幂等受理；相同请求返回原任务/回执，不同内容复用同号 MUST 拒绝。已经完成的生成结果不重新调用模型，结果未知时 SHALL 核对原任务而非创建替代执行。

#### Scenario: 发布已提交但响应丢失
- **WHEN** 调用者用相同 requestId 和相同版本内容重试
- **THEN** 系统返回原发布回执和实际状态，不新增第二次发布或丢失审核记录

### Requirement: 已发布知识使用当前范围和依赖检查

检索、详情、导出和 Agent 使用 SHALL 核验当前 Auth 签名上下文/freshness、当前用户范围、目标 revision 状态及每项来源的当前用途授权。知识来源依赖 SHALL 至少记录 `grantId`、`assetId`、`snapshotHash`、`contentHash`、内容/脱敏修订和数据模式；用途授权撤回、快照不匹配、来源删除/判定无效或当前范围失效时，未发布、已撤回或无权访问的正文 MUST 不得进入匹配、摘要、诊断或响应。Interview 的公开 `visibility` 变化不取代知识用途授权状态。没有实现的组织范围能力必须明确不可用。

#### Scenario: 用户知道另一门店的知识 ID
- **WHEN** 当前用户提供不在其可读范围内的知识 ID
- **THEN** 系统拒绝读取正文，不能按 ID 已存在或调用者是店长推定可见

#### Scenario: 来源用途授权被撤回
- **WHEN** 一个发布 revision 的任一逐字稿来源用途授权被撤回或来源被删除/判定无效
- **THEN** published/check 返回 `NOT_ACCESSIBLE`，search/detail 不返回该 revision 正文

#### Scenario: 来源快照发生变化
- **WHEN** 来源仍可访问，但当前 snapshot/content hash 或内容/脱敏修订与已发布 revision 绑定值不符
- **THEN** published/check 返回 `STALE`，search/detail 不返回旧派生正文，要求重新核对和审核

#### Scenario: Interview 来源服务不可用
- **WHEN** 来源当前状态因 Interview 服务故障而无法核验
- **THEN** published/check 返回 `UNAVAILABLE`，不能把故障伪装为无匹配或可继续使用

### Requirement: 发布范围只能来自 Auth 身份与来源授权的交集

知识授权 SHALL 取当前已验签 Auth 身份/操作对应的可信业务范围与所有来源用途授权目标范围的交集。请求体中的 `storeId`、`ownerId`、员工号、模型输出或知识 ID MUST NOT 扩大范围。当前 Auth context 只含受支持且已验证的业务身份；如果请求门店范围但没有可信 `STORE` identity 映射，发布/搜索/读取/check MUST 失败关闭且不返回正文。未来接入范围能力须由 Auth 注册精确操作和可信身份映射后再启用。

#### Scenario: 请求体伪造门店范围
- **WHEN** 调用者在请求中提供一个 Auth context 与来源授权都未证明的 `storeId`
- **THEN** 系统拒绝该范围并且不读取或返回目标知识正文

#### Scenario: 当前 Auth 没有可信 STORE identity
- **WHEN** 请求要求门店级发布或读取，但已验签上下文没有可信 `STORE` identity
- **THEN** 系统以不可访问拒绝，而不是回退到全局、调用者自报门店或空 scope

### Requirement: 知识检索保持领域真源责任

系统 SHALL 支持按经营问题、场景、标签和关键词查询当前获准已发布知识，合法无匹配与来源/存储不可用分别表达。语义索引可作为派生能力，MUST NOT 替代知识域的最终状态和授权复核，也不得自行读取个人 Memory 以补充缺失正文。

#### Scenario: 检索未命中与服务故障
- **WHEN** 查询成功但没有可读知识，或依赖访问失败
- **THEN** 前者返回合法空结果，后者返回可辨识不可用，二者不会互相伪装

### Requirement: 领域知识发布与 Memory 共享保持独立

发布经营知识 SHALL 仅授予该知识契约允许的范围，不自动开放访谈私有会话、个人偏好历程或 Memory 共享经验。新官方知识 Agent MUST 使用必需 Memory 生命周期及精确注册身份，基础 Memory 失败时在模型/工具执行前拒绝。

#### Scenario: 知识 Agent 缺少 Memory 授权
- **WHEN** 官方知识 Agent 的基础工作集读取或当前范围核验失败
- **THEN** 本轮模型和业务工具均不执行，不能因知识服务可查询而走无记忆旁路

#### Scenario: 运营人员发布一个案例
- **WHEN** 知识案例审核发布完成
- **THEN** 系统保留知识发布回执，不自动生成个人偏好或跨用户 Memory 共享经验授权

### Requirement: 已发布知识通过冻结 v1 公开契约消费

知识域 SHALL 提供 domain-parallel-v1 的 published/search、published/batch-read 和 published/check 三个 POST 契约。包络、路径和字段遵循并行总览，知识引用包含 knowledgeId/revision/contentHash，快照保留发布状态、模式、dataset、来源引用及获准派生正文；不得返回私有逐字稿全文。check 必须是 status-only 且不返回正文，只读区分 `CURRENT`、`STALE`、`NOT_ACCESSIBLE`、`UNAVAILABLE`；访问整体无效必须拒绝而非空匹配，具体状态语义沿用并行总览。

#### Scenario: 经营消费者尚未实施
- **WHEN** 本域已具备获准的模拟访谈来源和知识发布版本，但没有经营或房源的新增实现
- **THEN** 当前受权用户仍能通过固定知识 v1 契约查询、读取并检查该版本，不要求其它 change 提供新的 Java 类型或服务

#### Scenario: 知识版本撤回后的当前检查
- **WHEN** 调用者检查一个已撤回或失去当前访问权的知识引用
- **THEN** 返回 NOT_ACCESSIBLE 且不带正文；来源依赖故障返回 UNAVAILABLE，不能把二者解释成可继续使用
