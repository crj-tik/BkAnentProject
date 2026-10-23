## Purpose

本能力定义业务域与Supervisor消费独立RAG的职责，保留领域真源、最终校验和排序、工具身份及历史审计，规定真实REST兼容、A2A专用身份传播和无消费者旧索引RPC的直接清理，避免多处执行或隐式放宽认证。


## Requirements

### Requirement: 域消费统一 RAG 而不保留后端执行链

房源、记忆及本次SubAgent SHALL 只通过RAG执行关键词/向量/embedding/融合/通用重排，保留语料、生命周期、权限及最终检查。房源SQL详情/列表与未接入的营销独立搜索 SHALL 保持原职责，不作为混合兜底。

#### Scenario: 房源知识工具执行
- **WHEN** 房源Agent调用searchListingsByKeyword
- **THEN** 工具以领域包装执行默认混合加重排，描述及技能说明升级语义，房源不执行ES召回

#### Scenario: RAG故障
- **WHEN** RAG不可用
- **THEN** 检索明确失败，不恢复旧后端实现，独立SQL功能仍可用

### Requirement: 领域最终检查和排序不可被通用重排替代

域 SHALL 取得有界足量候选及scoreKind/scoringVersion，按领域规则决定最终topK，不混用原始异量纲分数、硬过滤与软衰减。返回正文、匹配与诊断前 SHALL 检查当前有效身份、资源状态、权限、投影版本和条件；ADMIN房源全读未删除（含非ON_SALE），BROKER仅ON_SALE；个人偏好包括ADMIN绝对self-only。补取 SHALL 至多一次且受累计预算约束，结果不足如实返回。

#### Scenario: 真源变化或用户失效
- **WHEN** 候选返回前房源删除/状态变化、偏好失效或用户停用/删除/离职/角色失效
- **THEN** 无效内容不进入任何输出字段；无效用户整个操作被拒绝，不凭旧token或旧scope继续

#### Scenario: 领域时间衰减
- **WHEN** 记忆启用软时间衰减且较旧候选高度相关
- **THEN** 时间影响排序而非自动删除，除非另有明确时间硬过滤

### Requirement: A2A 认证使用专用可验证信标与真实传输

A2A调用 SHALL 通过真实受支持transport传播专用auth可验信标，绑定可信服务/当前用户/请求/audience/短有效期，并在同步、流式、任务查询取消及异步续接操作验证。凭据及专用上下文 MUST NOT 进入模型metadata、工具参数、普通structuredContext、日志或checkpoint。进程内租约过期/重启 SHALL 要求原owner重新认证，不按userId换发；缺专用transport/信标或当前身份失效 SHALL 在业务操作前失败关闭。

#### Scenario: 真实房源Agent读取RAG
- **WHEN** 有效用户经Supervisor委托房源Agent
- **THEN** 实际A2A调用携带可验专用身份并完成领域RAG调用，模型上下文/持久快照没有凭据

#### Scenario: 伪造metadata或恢复旧凭据
- **WHEN** 模型提供userId/auth metadata，或旧任务重启后没有当前有效专用凭据
- **THEN** 不接受其为身份，业务/RAG调用零执行并要求认证

### Requirement: Supervisor知识工具保留治理和审计身份

milvusKnowledgeSearch SHALL 保留稳定能力、query/topK/知识开关/格式化及审计字段，只代理获准空间；模型不得选择物理目标。成功、无匹配和确定只读失败 SHALL 区分，不把失败伪装权限拒绝或副作用未知。历史checkpoint SHALL 只读兼容，已完成结果不重发，不恢复旧runner。

#### Scenario: 禁用知识或读取历史
- **WHEN** 请求关闭知识工具，或查询迁移前milvusResults记录
- **THEN** 禁用时零RAG执行并可审计；历史字段仍可展示但不依赖旧客户端或恢复执行

### Requirement: 真实旧REST保持受鉴权纯代理兼容

六组旧GET搜索、POST集合初始化及房源index/query路径方法和成功字段 SHALL 在真实消费者兼容期保留纯代理；身份校验与ADMIN唯一管理/偏好self-only规则不可绕过。collectionName SHALL 仅固定别名转换，维度严格匹配，任意物理目标拒绝；退役另行公告，不和无消费者旧RPC捆绑。

#### Scenario: 旧客户端数量和别名
- **WHEN** 获准旧房源查询缺省数量或提交受支持工具topK
- **THEN** 保留旧20+20/最终10或工具最终topK语义并生成合法重排池，非法数量/集合/维度明确拒绝

### Requirement: 无消费者旧索引RPC及遗留证明直接清理

确认没有旧消费者后系统 SHALL 直接删除syncListingKnowledge/deleteListingKnowledge的签名、实现和引用，同时清理无生产调用的signed listing legacy reconcile/证明路径，不创建条件成功、永久失败兼容或等待虚构RPC兼容周期。新writer SHALL 直接领域outbox到RAG；正常index/backfill/outbox及RAG实际hash补偿 SHALL 保留。来源执行类/bean/依赖/重复bootstrap及fallback SHALL 同次清理，真实REST/工具纯包装可继续保留。

#### Scenario: 清理无调用入口
- **WHEN** 全量调用方搜索确认旧RPC/遗留证明无消费者
- **THEN** 在本次开发发布删除该路径，不新增LEGACY_RECONCILIATION_REQUIRED成功验收要求，不误删其他AgentRpcService方法或worker补偿

### Requirement: 新记忆检索与详情必须经过记忆域授权

Agent 消费会话档案、摘要、偏好历程、经验或协作导出 SHALL 经过 memory 域服务，语义召回 SHALL 只由 RAG 执行；按稳定 ID 读取 SHALL 由 memory 真源授权，不要求索引先就绪。Agent MUST NOT 直接向 RAG 搜索私有记忆以绕过 memory 最终检查，也 MUST NOT 在 RAG 故障时自行恢复旧关键词/向量执行链。

#### Scenario: 新偏好逻辑块语义命中
- **WHEN** 一个 Agent 以当前获准事项搜索相关偏好
- **THEN** memory 代理 RAG 取得候选并最终核验真源，返回单个逻辑块的当前状态、版本和历程读取信息，而不是让 Agent 直接消费未核验分片

#### Scenario: 新共享记录尚未索引
- **WHEN** 共享导出已持久化且授权调用者按稳定 ID 请求详情，但索引仍待应用
- **THEN** memory 在当前权限与真源版本核验后返回详情，不把索引未就绪等同于正文不存在

### Requirement: 私有记忆具备可信域与参与者范围

新记忆读取 SHALL 在 owner-only 基础上施加可信调用服务、domain/agent、conversation/matter 和显式共享 grant 的授权交集；个人偏好包括 ADMIN 仍 self-only。范围 SHALL 来自可验证服务身份和授权关联，MUST NOT 依赖模型填写的 userId/domain、namespace、category、普通 metadata 或引用取得读取权限。授权过滤 SHALL 在双路召回与重排前生效；候选正文实际送入重排前 SHALL 依据记忆域当前权威状态核验其记录版本、删除、grant和派生来源失效状态，无法核验 MUST 失败关闭，并在最终输出前再次核验当前身份与范围。

#### Scenario: 同用户不同私有域
- **WHEN** 营销 Agent 与访谈 Agent 使用同一用户身份，而营销 Agent 未取得访谈私有记录共享授权
- **THEN** 营销召回、重排、详情及诊断均不接触访谈私有正文，不因 owner 相同自动共享

#### Scenario: 伪造共享 grant 或后台身份搜索
- **WHEN** 模型或后台 worker 提交未被可信服务授予的 domain/grant 或尝试通用用户记忆搜索
- **THEN** 操作在未授权正文读取前被拒绝，后台加工身份不能继承用户通用检索权

### Requirement: 真源核验与领域选择适用于新记忆类型

memory SHALL 在返回所有新记忆匹配、正文、证据和诊断前检查当前 owner、域授权、删除/撤回状态、逻辑版本和内容哈希。相关度、偏好置信度、当前活跃权重及时间衰减 SHALL 分开解释并由域执行最终选择，RAG MUST NOT 代替域应用记忆衰减。补取 SHALL 保持既有至多一次及累计预算限制，结果不足如实返回；依赖不可用 MUST NOT 伪装成无匹配。

#### Scenario: 旧历程分片或协作授权已失效
- **WHEN** RAG 返回旧偏好 revision 分片或已撤回的共享导出候选
- **THEN** memory 根据最新真源排除无效候选及关联证据，不能拼成跨版本逻辑块，也不能在诊断中泄漏被排除正文

#### Scenario: 高相关旧经验参与排序
- **WHEN** 获准经验候选的相关度较高但有效确认较旧
- **THEN** memory 依据版本化领域规则分别计算相关度与时间权重，读取不会成为新确认，RAG 原始分数不被误当领域最终分数

### Requirement: 记忆正文只作为有界数据使用

注入 Agent 的记忆 SHALL 携带来源和版本，保留相关事实、决定、否定或临时例外，并在明确 token 预算内装配。检索正文 MUST NOT 被解释为更高优先级政策、认证材料、工具批准或隐式业务计划；脱敏映射、凭据及未经许可的领域原文 MUST NOT 因可检索而进入模型或前端。

#### Scenario: 经验文本包含指令性内容
- **WHEN** 检索经验中包含“忽略权限”或要求自动调用其他 Agent 的文字
- **THEN** 该文本没有改变授权、审批或调用选择权的效力，运行仍遵循当前硬规则与模型工具治理

#### Scenario: 历史补忆返回有证据解释
- **WHEN** 压缩后缺失对象的授权检索命中当前有效证据
- **THEN** Agent 取得有界事实解释及来源引用，不能以语义相似为由编造历史或扩大私有会话范围
