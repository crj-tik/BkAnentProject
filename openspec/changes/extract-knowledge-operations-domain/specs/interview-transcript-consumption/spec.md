# 访谈逐字稿跨域消费契约

## Purpose

定义知识运营消费访谈逐字稿时的来源所有权、当前读取范围、快照完整性及失效行为。下游能够以稳定证据构建经营知识，同时让访谈继续拥有会话和逐字稿事实，避免复制原稿、跨库访问或来源评级替代访问授权。

## ADDED Requirements

### Requirement: 访谈域保有逐字稿权威身份

访谈域 SHALL 独占会话、话轮、原始/脱敏逐字稿正文、来源 hash、内容/脱敏修订及来源状态。逐字稿消费 SHALL 使用访谈域公开的只读来源契约和稳定资产引用，包含来源资产 ID、snapshotHash、contentHash、contentRevision、sanitizationRevision、评级、数据模式及可核验的状态。下游 MUST NOT 通过直读 `bk_interview`、写入访谈事实或新增一份可编辑原稿来绕过该契约。

#### Scenario: 知识运营引用现有逐字稿
- **WHEN** 运营人员选择一份访谈逐字稿作为知识来源
- **THEN** 下游保存访谈来源引用及派生材料，原逐字稿和会话状态继续由访谈域维护

### Requirement: 授权先于正文读取且评级不授予权限

来源元数据、正文及引用使用 SHALL 绑定 Auth 签名 `AuthorizationContext`、`AuthzFreshness`、调用服务、用途和资源范围。服务间请求 SHALL 使用调用方身份证明，并绑定完整请求体、目标受众、调用方、操作和请求 ID；actor MUST 只能取自本地验签后的 Auth context，不能来自模型参数或客户端 `userId`。访谈服务 SHALL 在读正文前验证当前 Auth context/freshness、精确操作、既有会话/案例关系及有效来源用途授权。L2/L3、`PUBLISHED` 可见性、已知 assetId、公开工具描述及同 owner 历史均不得自动授予知识用途读取权。

#### Scenario: 已归档但未获准跨域读取
- **WHEN** 一份 L3 逐字稿存在，但当前知识调用者没有其有效读取范围
- **THEN** 访问被拒绝且正文未读取，不因评级高或已知 ID 放行

### Requirement: 知识用途授权必须明确、可撤回且绑定精确来源

访谈域 SHALL 提供独立的来源用途授权事实，绑定 `assetId`、`snapshotHash`、`contentHash`、`contentRevision`、`sanitizationRevision`、用途 `knowledge_derivation`、唯一消费服务 `knowledge-service`、授权目标知识范围及签发主体。签发/撤回 SHALL 验证当前 Auth 签名上下文/freshness、精确已登记操作和当前主体对来源案例的既有访问关系。来源快照/内容/脱敏修订变化、来源被删除/判定无效、授权撤回或授权范围不可核验时，授权 MUST 失效；不得仅靠知识域缓存或发布状态继续提供正文。该用途授权与现有 `visibility=PRIVATE|PUBLISHED` 生命周期分离：发布或撤回公开可见性本身既不签发也不撤销知识用途授权。

授权读正文 SHALL 同时验证调用服务 proof 和当前来源授权；不存在授权或授权不匹配时，拒绝必须发生在读取脱敏正文之前。来源授权只允许派生知识用途，不能改写访谈资产的发布状态或访谈公开 MCP 权限。

#### Scenario: 缺少知识用途授权
- **WHEN** 知识服务请求一个当前可读但未授予 `knowledge_derivation` 的逐字稿
- **THEN** 访谈域在读取正文前拒绝请求，且不因 `interview.read`、L3 或 `PUBLISHED` 可见性补发授权

#### Scenario: 授权后内容修订
- **WHEN** 授权绑定的 contentHash、contentRevision 或 sanitizationRevision 与当前资产不同
- **THEN** 授权不再匹配，服务拒绝读取并要求重新显式授权

### Requirement: 使用精确快照并核验内容一致性

正文读取 SHALL 绑定期望 snapshotHash，服务端输出前 SHALL 核验所读内容和当前来源状态一致；下游必须保留实际使用版本及证据定位。来源 hash 冲突 MUST 明确报告，不能自动换用另一份近似材料。

#### Scenario: 生成期间来源快照变化
- **WHEN** 知识任务期望的逐字稿 hash 与当前来源不一致
- **THEN** 系统拒绝将新正文当作旧来源继续生成，要求按新来源重新受理或核对原任务

### Requirement: 来源失效使派生使用失效

来源删除/失效、知识用途授权撤回、精确快照变化或状态不可核验时 SHALL 阻止新的派生正文读取、入模、引用和输出；原业务操作与审核事实可保留。撤回访谈公开可见性本身不改变独立知识用途授权。通知延迟或缓存命中 MUST 不得豁免使用前当前复核。

#### Scenario: 已发布打法依赖的来源资产失效
- **WHEN** 店长准备使用的打法来源逐字稿已删除或被访谈域判定为不可用
- **THEN** 系统停止提供该来源及依赖正文，返回来源失效信息，不能因打法曾经发布继续展示

#### Scenario: 来源用途授权被撤回
- **WHEN** 一个已发布打法引用的知识用途授权已被撤回
- **THEN** 当前检查标记依赖失效，知识域停止提供该打法派生正文；访谈授权审计事实仍保留

### Requirement: 来源模式与脱敏域贯穿派生内容

逐字稿消费 SHALL 使用访谈域的脱敏正文，敏感映射和凭据不得进入派生内容。模式 SHALL 只来自服务端可信 provenance；不得从 `AUTO`/`UPLOAD`、L0–L3、发布状态或用户输入推断。当前 `interview_asset` 未持久化 `dataMode`，因此无独立可信 provenance 的存量资产 MUST 返回 `UNKNOWN`；测试夹具可以显式标记 `SIMULATED`。添加模式元数据不得改写既有正文、`snapshotHash` 或内容修订。派生结果不得自动提升成真实经营效果证据。

#### Scenario: 用模拟访谈生成打法候选
- **WHEN** 来源逐字稿来自模拟场景
- **THEN** 候选和引用均保留模拟标记，原声核验在同一脱敏快照内进行，不输出映射表或真实效果证明

#### Scenario: 历史资产来源模式未知
- **WHEN** 一个旧资产只有 `source=AUTO`、`archiveGrade=L3`，没有服务端可信 provenance
- **THEN** 来源契约返回 `UNKNOWN`，不能将它作为真实经营效果证据
