# 经验知识运营契约

## Purpose

支持知识运营人员把当前已发布的脱敏访谈整理为可审核、发布、引用和撤回的经营案例与打法，并向获准用户提供带来源、适用边界和版本的经营支持。

## ADDED Requirements

### Requirement: 经营知识具有独立身份和结构化证据

系统 SHALL 为经营案例和打法分配 Knowledge 自有身份及不可变 revision，记录经营问题、情境、前提、动作、来源所述结果、适用边界、未核实项和来源证据。source asset ID、Interview report ID 与 knowledge ID MUST 保持不同语义。

#### Scenario: 同一来源产生新的运营版本
- **WHEN** 运营人员修订一个已存在的打法
- **THEN** 系统创建可追溯的新 revision 并保留前版及来源，不覆盖 Interview 逐字稿或原报告

### Requirement: 知识域与访谈域保持独立事实所有权

Knowledge SHALL 独占新经营案例、打法、不可变 revision、审核决定、发布范围、来源依赖、生成任务和操作回执。Interview 继续独占原报告及报告任务、会话、话轮和逐字稿；reportId/assetId MUST NOT 被复用为 knowledgeId。旧 playbook_card/commonality_report 只盘点并留在 Interview 域，不搬表、不复用 ID 或固定置信度，不在本 change 迁移存量。v1 可以记录不透明 report reference，但 MUST NOT 读取报告正文或重放/覆盖其任务。Knowledge 不得写回 Interview 数据。

#### Scenario: 遇到 Interview 域旧打法表
- **WHEN** 本域盘点 Interview 的 playbook_card 或 commonality_report
- **THEN** 旧表及 ID 留在 Interview 域，Knowledge 使用新 ID 创建资产并保留来源映射清单

### Requirement: 原声和经营效果证据分别核验

原声引用 SHALL 对照精确来源快照核验；来源评级、案例成功叙述、模型质量分和重复样本数量 MUST NOT 单独成为已证实经营效果的依据。归纳 SHALL 保留出处、反例或缺失说明，并区分自述、已核验和未验证结果。

#### Scenario: 多个案例提到相同做法
- **WHEN** 两个或更多案例出现相同做法但缺少独立效果证据
- **THEN** 系统可以形成有来源的候选归纳，但不能仅按样本数量标记为已验证有效

### Requirement: 编辑审核和发布遵循当前权限与版本

知识 SHALL 提供 DRAFT、REVIEW_PENDING、PUBLISHED、WITHDRAWN 状态；编辑创建新 revision，提交、审核、发布、撤回、读取和生成均 SHALL 核验对应精确 Auth 操作、验签 AuthorizationContext/AuthzFreshness 与 expectedVersion，并记录实际 actor 和决定。A2A 能力 a2a:knowledge-agent 仅映射 agent.workflow.invoke.knowledge；Agent invoke 权限 MUST NOT 代替知识生命周期动作权限。审核者分离要求由受管审核策略控制，模型生成不得替代人工审核发布决定。当前 Auth 未提供可信 STORE identity 时，门店范围动作 MUST 失败关闭。

#### Scenario: 两人并发提交或审核
- **WHEN** 一个请求基于旧 revision 提交审核或发布
- **THEN** 系统返回可辨识版本冲突，保留已接受的新修订和原回执，不覆盖后续内容

#### Scenario: Agent invoke 权限不能替代发布权限
- **WHEN** 调用者有 agent.workflow.invoke.knowledge，但当前验签上下文没有目标知识范围所需的发布操作
- **THEN** 发布动作被拒绝且知识状态不发生变化

#### Scenario: Auth 未提供可信 STORE 身份
- **WHEN** 请求门店级发布或读取而 Auth 上下文没有已验证 STORE identity
- **THEN** 系统拒绝操作，不从请求体、角色名称或测试夹具推断门店范围

### Requirement: 生成任务和操作回执可可靠找回

生成和运营动作 SHALL 以稳定 requestId、目标对象和内容 hash 幂等受理；同一请求返回原任务/回执，不同内容复用同号 MUST 拒绝。已完成结果不得重新调用模型，结果未知时 SHALL 核对原任务而不是创建替代执行。

#### Scenario: 发布成功但响应丢失
- **WHEN** 调用者使用相同 requestId 和相同 revision 内容重试
- **THEN** 系统返回原发布回执和实际状态，不新增第二次发布或丢失审核记录

### Requirement: 来源读取使用当前已发布脱敏快照

Knowledge 只允许在明确的导入或生成动作中按需读取 Interview 当前 PUBLISHED 且符合 L2/L3、脱敏版本、哈希、删除状态及案例/会话关系门槛的来源，并且只读取脱敏 content。系统 SHALL 记录 assetId、snapshotHash、contentHash、contentRevision、sanitizationRevision、publicationRevision、dataMode 和 quoteHash/定位符。PUBLISHED 是本期来源准入信号，不授予用户执行 Knowledge 操作的权限；私有、已删除、过期或无法核验的来源不得进入模型或新 revision。Knowledge MUST NOT 读取 rawContent 或敏感映射，也 MUST NOT 把来源正文写入 Memory。

#### Scenario: 导入私有来源
- **WHEN** 用户发起导入但 Interview 当前来源为 PRIVATE 或已删除
- **THEN** 来源不被读取或送入模型，Knowledge 记录 NOT_ACCESSIBLE

#### Scenario: 源数据库不可用
- **WHEN** Knowledge 无法读取或复核 Interview 来源当前状态
- **THEN** 来源动作返回 UNAVAILABLE，不复用缓存快照继续生成或返回旧派生正文

### Requirement: 已发布知识使用当前权限和来源依赖

检索、详情、导出、published、batch-read、check 和 Agent 输出 SHALL 核验当前 Auth 签名上下文/freshness、actor、精确操作、目标 revision 状态与资源范围，并在使用前复核每项来源。来源 PRIVATE/删除/失去合格状态时返回 NOT_ACCESSIBLE；来源 hash 或内容/脱敏/发布修订不符时返回 STALE；来源检查服务不可用时返回 UNAVAILABLE。上述状态 SHALL 阻止依赖正文进入匹配、摘要、诊断或响应。缓存、通知和索引不得取代当前复核。check 为 status-only，不返回正文。

#### Scenario: 用户知道范围外知识 ID
- **WHEN** 当前用户提供不在其可读范围内的知识 ID
- **THEN** 系统拒绝正文读取，不能按 ID 已存在或用户自称店长推定可见

#### Scenario: 已发布来源被撤回
- **WHEN** 已发布打法依赖的 Interview 来源被撤回或失去合格状态
- **THEN** published/check 返回 NOT_ACCESSIBLE，search/detail/batch-read 不返回依赖正文

#### Scenario: 来源版本变化
- **WHEN** 当前 snapshot/content hash 或内容、脱敏、发布修订与知识 revision 绑定值不符
- **THEN** published/check 返回 STALE，旧派生正文不再提供，要求重新核对和审核

#### Scenario: 来源状态不可确认
- **WHEN** Interview 来源数据库不可用或当前状态查询失败
- **THEN** published/check 返回 UNAVAILABLE，不伪装成合法空结果或继续输出旧正文

### Requirement: 发布范围来自当前 Auth 身份和 Knowledge 资源授权

知识发布和读取范围 SHALL 来自当前已验签 Auth 身份、精确操作及已验证业务身份。请求体中的 storeId、ownerId、员工号、模型输出、source PUBLISHED 状态或知识 ID MUST NOT 扩大范围。当前 Auth 未提供可信 STORE identity 时，门店级发布、搜索、读取和 check MUST 失败关闭；own 范围还需核验 Auth actor 与 Knowledge 记录所有权。

#### Scenario: 请求体伪造门店范围
- **WHEN** 调用者提交一个 Auth 上下文未证明的 storeId
- **THEN** 系统拒绝该范围且不读取或返回目标知识正文

#### Scenario: 来源可公开但用户无 Knowledge 读取权限
- **WHEN** Interview 来源为 PUBLISHED，但当前用户没有目标知识范围的读取操作
- **THEN** 系统拒绝返回知识正文，不把来源公开状态当作 Knowledge 权限

### Requirement: 知识检索保有领域真源责任

系统 SHALL 支持按经营问题、场景、标签和关键词查询当前获准发布的知识。合法无匹配与来源、存储或授权依赖不可用必须分别表达。语义索引如后续接入只能作为派生能力，不得替代 Knowledge 最终状态/授权复核，也不得读取个人 Memory 补充缺失正文。

#### Scenario: 检索未命中与服务故障
- **WHEN** 查询成功但没有当前可读知识，或来源/存储检查失败
- **THEN** 前者返回合法空结果，后者返回可辨识故障状态，二者不互相伪装

### Requirement: knowledge-agent 必须使用隔离且必需的 Memory 生命周期

官方 knowledge-agent SHALL 使用 caller=knowledge-service、domain=knowledge、agentId=knowledge-agent 的 A2A Memory 生命周期。Memory 工作集读取和当前来源验证必须发生在模型及业务工具执行之前；必需 Memory 不可用、身份不匹配或当前范围无法验证时，本轮模型及业务工具不得执行。Agent 完整轮次结束时 SHALL 按既有生命周期复核 Memory 来源并完成允许的会话归档。Knowledge 只能使用该 agent/domain 的授权记忆，不得将逐字稿原文、原声 quote、敏感映射或完整知识 revision 正文写入 Memory；Agent 工具参数和响应只能携带不透明来源引用、任务状态和受限摘要。知识发布不得自动创建 Memory 共享经验或 grant。

#### Scenario: Memory caller 未登记
- **WHEN** Knowledge 身份 key 或 knowledge / knowledge-agent Memory 白名单缺失
- **THEN** Memory 请求失败且模型和业务工具零执行

#### Scenario: 必需 Memory 工作集不可用
- **WHEN** 工作集加载、当前来源复核或必需归档失败
- **THEN** Agent 不走无记忆旁路，调用失败且不得释放未验证结果

#### Scenario: Agent 工具返回来源正文
- **WHEN** Knowledge 内部工作流读取逐字稿并验证原声引用
- **THEN** Agent/Memory 消息只包含来源引用、任务状态和受限摘要，逐字稿正文、原声 quote 与完整 revision 正文均不进入 Memory 归档

#### Scenario: 发布一个知识案例
- **WHEN** 知识案例审核发布完成
- **THEN** 系统只记录知识发布回执，不自动生成个人偏好或跨用户 Memory 共享经验授权

### Requirement: 已发布知识通过冻结 v1 HTTP 契约消费

Knowledge SHALL 提供 domain-parallel-v1 的 published/search、published/batch-read 和 published/check 三个 POST 契约。包络、路径与字段遵循并行总览；引用包含 knowledgeId/revision/contentHash，快照保留发布状态、dataMode、dataset、来源引用和获准派生正文；不得返回私有逐字稿全文。check SHALL 仅返回状态并区分 CURRENT、STALE、NOT_ACCESSIBLE、UNAVAILABLE。访问整体无效必须拒绝而非空匹配，具体状态语义沿用并行总览。

#### Scenario: 经营消费者尚未实施
- **WHEN** 本域已具备获准测试来源和已发布知识版本，但下游经营消费者尚无新增实现
- **THEN** 当前获准用户仍可通过固定知识 v1 契约查询、读取并检查该版本，不要求其它 change 先发布 Java 类型或服务

#### Scenario: 知识版本撤回后检查
- **WHEN** 调用者检查已撤回或失去当前访问权的知识引用
- **THEN** 返回 NOT_ACCESSIBLE 且不含正文；来源检查不可用返回 UNAVAILABLE，不得解释成可继续使用