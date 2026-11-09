# 访谈逐字稿跨域消费契约

## Purpose

定义 Knowledge 读取 Interview 已发布脱敏逐字稿时的来源准入、版本完整性、授权边界和失效行为，使知识派生可追溯且不改变 Interview 的事实所有权。

## ADDED Requirements

### Requirement: Interview 保有逐字稿事实所有权

Interview SHALL 保有会话、话轮、逐字稿原文和脱敏正文、来源版本及发布状态。Knowledge SHALL 只维护来源引用、必要的脱敏派生材料及操作审计，不得创建可编辑的第二份逐字稿，不得写入 Interview 数据。Knowledge 的来源读取 MUST NOT 访问原始正文或敏感映射。

#### Scenario: 运营人员引用已发布逐字稿
- **WHEN** 具有 Knowledge 来源使用权限的运营人员选择一份已发布逐字稿
- **THEN** Knowledge 记录 Interview asset 引用和精确快照信息，Interview 的源正文、会话和状态保持不变

#### Scenario: 尝试读取私有或原始内容
- **WHEN** 来源当前为 PRIVATE、已删除，或请求涉及 rawContent/敏感映射
- **THEN** 系统不向 Knowledge、模型或调用者返回相应正文

### Requirement: 只有当前公开合格快照可用于知识派生

Knowledge SHALL 仅在来源当前为 PUBLISHED、评级为 L2/L3、资产和关联案例未删除、脱敏修订达到支持版本、正文哈希匹配且来源案例/会话关系有效时读取脱敏 content。PUBLISHED 是本期来源准入信号，不替代当前用户执行 Knowledge 操作所需的 Auth 签名上下文、freshness、精确操作和资源范围。Knowledge MUST 在明确的导入或生成动作中按需读取，不得自动批量导入全部已发布来源。读取失败时 MUST 不将来源内容传入模型或后续工具。

#### Scenario: 来源发布后被撤回
- **WHEN** Interview 将一个已发布来源改为 PRIVATE 或删除来源
- **THEN** 后续来源读取和依赖检查不返回正文，Knowledge 停止该来源派生内容的使用

#### Scenario: 来源评级或脱敏版本不满足门槛
- **WHEN** 来源评级不是 L2/L3、脱敏修订低于支持版本、案例/会话关系失效或正文哈希不匹配
- **THEN** 系统拒绝读取或使用正文，并返回可区分的来源状态

### Requirement: 来源引用绑定不可变快照和证据定位

Knowledge 每项来源依赖 SHALL 记录 assetId、snapshotHash、contentHash、contentRevision、sanitizationRevision、publicationRevision、数据模式及可复核的 quoteHash/定位符。导入和输出前 MUST 对照当前来源元数据及预期版本；内容只允许使用脱敏 content，rawContent MUST NOT 进入 common wire DTO、Knowledge 存储、Memory 或模型。正文哈希冲突或版本变化不得自动替换成另一份近似来源。

#### Scenario: 生成期间来源修订
- **WHEN** 生成任务绑定的内容、脱敏或发布修订与当前来源不同
- **THEN** 系统将来源判定为过期，不沿用旧绑定继续生成或发布

#### Scenario: 原声引用不能定位到快照
- **WHEN** 候选中的原声引用无法在已绑定的脱敏快照中精确核验
- **THEN** 系统拒绝把该引用登记为已核验来源证据

### Requirement: 来源状态不可确认时停止派生和输出

来源当前为 PRIVATE、已删除或不具备知识使用资格时，Knowledge SHALL 返回 NOT_ACCESSIBLE；当前哈希或内容/脱敏/发布版本与绑定值不匹配时 SHALL 返回 STALE；来源数据库不可用或检查无法完成时 SHALL 返回 UNAVAILABLE。上述状态均 MUST 阻止新派生、检索正文、详情、导出和 Agent 输出。缓存、旧发布状态和失效通知不能取代每次使用前的当前检查。

#### Scenario: 已发布打法依赖的来源撤回
- **WHEN** 店长查询的已发布打法依赖来源已撤回或失去读取资格
- **THEN** Knowledge 返回 NOT_ACCESSIBLE 且不返回该依赖的派生正文

#### Scenario: 来源数据库检查失败
- **WHEN** Knowledge 无法连接 Interview 来源数据库或完成当前状态检查
- **THEN** Knowledge 返回 UNAVAILABLE，不将故障伪装为空结果或继续输出旧正文

### Requirement: 来源模式来自可信 provenance

来源 dataMode SHALL 只能来自服务端可信 provenance，不能从 AUTO/UPLOAD、L0-L3、PUBLISHED 或用户输入推断。当前 Interview 资产没有持久化 dataMode；没有独立可信 provenance 的存量来源 MUST 标记 UNKNOWN，受控测试夹具可以明确标记 SIMULATED。新增模式描述不得改写 Interview 正文、snapshotHash 或内容修订；派生结果不得自动提升为真实经营效果证据。

#### Scenario: 历史来源只有采集类型和评级
- **WHEN** 一个旧资产只有 source=UPLOAD 或 AUTO 及 L2/L3 评级，没有可信 provenance
- **THEN** 来源和派生知识均标记 UNKNOWN，不得作为已证实的真实经营效果

#### Scenario: 模拟来源生成打法候选
- **WHEN** 受控测试来源明确标记 SIMULATED
- **THEN** 候选和引用保留 SIMULATED 标记，且不产生真实效果证明