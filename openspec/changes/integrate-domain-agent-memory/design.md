## Context

动机见 proposal.md。当前与设计有关的事实：

- Supervisor 使用通用 StateGraph，run 内 StoredModelMessage 随 DatabaseCheckpointSaver 持久化；PrepareContext 当前不加载跨 run 会话摘要。saver 保留 MemorySaver 内存态，但不是 session 记忆缓存。
- SubAgent 以 ReactAgent.model(chatModel) 接共享认证、技能与上下文拦截器；未显式接入会话记忆或总结。框架版本为 Spring AI 1.1.2 / Alibaba 1.1.2.3。该版本存在 SummarizationHook，需显式启用；MessageWindowChatMemory 只截断、saveAll 替换窗口，不保存全量档案。
- SessionSharedMemoryService 整体覆盖 memory_json/summary，主要生产用途是 session ownership；snapshot API 不是自动压缩器。偏好 upsert 覆盖单值，现有 decayConfidence 每次减 0.05；系统约束有 active，无已接通的域经验学习链。
- MemoryRagSearchFacade 先 RAG 召回再查 SQL 真源；当前投影只有 memory.preference / memory.constraint。新档案/历程/经验不能直接冒用旧 schema。
- A2A 文本/Artifact 到 agent.delta、status 到 agent.progress、终态到 agent.completed；A2aExecutionService 直接 publish，SessionStreamService 审计/回放后 SseEmitter 发送。没有逐 delta memory 正文读取。Observe 中成果保存成功后才发布 artifact.created/tool.completed；Studio 详情可能同步 memory 成果，SSE 与成果读取不是同一延迟。
- LR-3/6 约束访谈运行面与脱敏；LR-17/18 禁止恢复业务 DAG、nextHints 自动调用和内部重复执行；LR-22/23 保持恢复身份；LR-31/38 要求检索集中、领域最终排序；LR-33/40/41 区分凭据、owner 和跨域授权；LR-53 保持成果/人工修订来源与时序。

证据入口：agent-service/orchestration/SupervisorToolLoopGraph、graph/official/DatabaseCheckpointSaver、service/A2aExecutionService、stream/InMemorySessionStreamService；memory-service/service/SessionSharedMemoryService、UserPreferenceMemoryService、MemoryRagSearchFacade；common-a2a/OfficialA2aAgentExecutor；marketing-content-service/a2a/MarketingOfficialA2aAgent。文档探索结果不是部署或性能验收。

## Goals / Non-Goals

**Goals:**
- 用框架已有生命周期管理 Agent 热工作集，memory 做持久真源、版本和授权检索，减少重复远程装配。
- 为压缩、偏好时间历程、历史补忆、经验选择与跨域共享给出可执行的数据/失败/并发契约。
- 多 Agent 在同 owner 下仍默认域私有；授权共享内容可经 A2A 直接交付，也能由参与 Agent 后续在 memory 读取。
- 本次覆盖所有已注册 SubAgent 的统一原生 A2A 接入口；Supervisor、marketing 和 interview 治理面进行代表性完整运行验收，其余 Agent 接线/隔离契约逐项验收。

**Non-Goals:**
- 不更换模型版本、不升级框架、不引入业务步骤 DSL/DAG 或新的规划器；事项仅是上下文生命周期标签，不掌握业务调用选择权。
- 不将访谈运行管线改成 ReactAgent，不搬迁逐字稿、业务状态、营销正文、Studio 人工修订或既有调用账本/checkpoint。
- 不把 ChatMemory 窗口、RAG、事件审计当作完整档案的唯一存储；不承诺跨库原子事务、传输 exactly-once、永久无丢 SSE。
- 不赋予 Supervisor 全域记忆读取权，不将跨用户经验自动公开，不启用 A2A pushNotification 或私有记忆前端直播。

## Decisions

### D1. 三层责任与统一薄适配

采用 Agent runtime / memory domain / rag execution 三层。common-memory 薄适配层承载生命周期适配与受管原生 Dubbo 客户端，独立建模块以实际依赖面和至少一个真实消费者验证为准；common 只放框架无关 DTO、错误枚举与签名契约。common-a2a 复用适配器但不将业务加工塞入 transport。相比所有服务强依赖全量 memory 框架或逐域复制客户端，薄层便于统一身份映射、缓存与测试；不存在真实消费者的 ChatClient 适配不建设。新 memory 服务间链路不新增 HTTP 请求，因此不借现有受管内网策略豁免新 HTTP 地址限制。

- Reactor/模型循环内的活跃窗口只存在于本域 Agent 工作集。
- memory 保存可持久化消息档案、摘要、事项/实体锚点、偏好聚合、经验与协作导出。
- memory 使用现有 outbox/RAG契约，RAG负责后端执行；稳定 ID 详情读取走 memory 真源，不等待索引。
- 框架扩展点按实际调用路径接入：ReactAgent 的 AgentHook 加载，ModelHook/SummarizationHook 在安全边界压缩，Interceptor装配授权投影；Supervisor 使用 PrepareContext/Observe 及相同策略服务。真正的 ChatClient 入口用 ChatMemoryRepository/Advisor 对接本地窗口，不把远程全历史直接绑定 saveAll，不在 Graph 累计消息之上重复注入。
- 原生 SummarizationHook 显式注册，保留 token 触发与消息替换语义；外围适配补工具数据序列化、安全切点、摘要校验、失败不替换与持久确认。禁止覆盖框架缓存或修改 .m2。

### D2. 可信身份、域私有与共享授权

定义 MemoryContextKey = (ownerId, domainId, agentId, conversationId)。定义 MatterId、runId、A2A taskId、domainSessionRef 为独立关联，不能相互当身份。domain/agent 由可信部署能力映射与经过验证的调用服务决定，模型只提出语义需求，不可指定他人的 owner/域来扩权。

memory v2 内部调用采用原生 Dubbo RPC（显式 dubbo consumer/provider、interface/group/version，不将 HTTP 服务发现实例适配为 REST，遵守 KI-100），覆盖工作集读取、消息交付、摘要提交、偏好/经验及共享详情/搜索。受管注册中心与可达网络由部署配置固定，模型和请求不可指定 host/URL。用户操作使用当前真实用户认证及专门绑定 memory 操作、caller、audience、requestId、bodyHash 的签名信封；既有 A2A 租约只提供当前身份来源，A2A 精确请求信标不能重放给 memory。新增 MemorySearchRequest/Response 与 MemoryAuthorizationScope，保留受鉴权 v1 REST/MemoryRagRpcService兼容但不以旧 HTTP 客户端接新链。重启后的用户读取必须由原 actor 重新认证。

可靠交付与用户读取分开：输入接受前通过当前认证取得 memory 服务端可持久核验的精确事件接收许可，绑定来源服务/owner/domain/agent/context、eventId、内容哈希和用途；调用接受时记录 invocationId 的受限结果交付许可。结果只有已接受调用的可核验完成事实可提交，模型参数不构成证明。延迟投递通过 SERVICE认证和该许可核验，仅能交付/核对这些事件，不能兑换用户搜索权或变更内容；许可不是 bearer token，不含用户凭据，不进入 prompt/SSE/普通metadata。用户租约失效不抹去既已授权的接收事实；服务重启后仍可补交付，但当前删除/撤回策略可阻止加工及可读发布。没有有效许可或可恢复事实源时不得承诺已接受可靠记忆记录。可选记忆增强不可用时允许原执行链按当前认证与政策继续，但明确本次消息未进入新档案且禁止压缩移除，不能用未记录降级宣称可靠归档已成功；该降级也不降低既有业务结果持久化要求。

范围枚举：DOMAIN_PRIVATE、TASK_SHARED、SESSION_SHARED；长期跨域偏好仅在用户明确授予具体范围时共享，缺省域私有。共享 grant 绑定 owner、事项/会话、来源、接收 agent/domain 集、purpose、版本与有效期；域内多个 Agent 亦只共享明确授权的组。所有入口（详情、搜索、导出、回放、后台处理）执行当前授权交集，ADMIN不得跨个人owner。

共享引用读取将经签名的接收方 contextKey 与来源 exportRef 分开；grant 的接收者同时绑定 domain、agent 和具体 conversation，TASK_SHARED 另绑定 matter，SESSION_SHARED 绑定独立 sharedScopeId。请求中的 scope/引用只用于核对服务端持久 grant，不授予权限；仅持展示或当前协作许可不能读取 durable export 正文。元数据授权通过后才读取正文，输出前重新检查当前用户及 grant/export 状态，远程 Auth 调用不占 SQL 锁。

RAG 新 memory scope 单独版本化，由 memory 签发并由 RAG 按可信调用者/空间/owner/domain/grant独立校验；不改旧 listing scope含义。私有空间按domain分配物理 ES/Milvus；同域存在需内容隔离的不同SubAgent时进一步分配各自独立索引/集合，不能只用agent标签替代既有物理隔离要求。空间内仍用不可覆盖owner/agent范围过滤；TASK/SESSION_SHARED 用独立协作空间且强制owner和grant过滤。namespace/category/tags只寻址，不是ACL。

替代方案“同user自动全域共享”和“模型填写domain”为拒绝项，会绕过既有owner-only以上的职责边界。

### D3. 数据模型与可靠增量

新增版本化结构（表名为实现命名基准，索引在实施中按访问计划验证）：

| 聚合 | 关键身份与语义 |
| --- | --- |
| memory_conversation | context复合身份、currentRevision、lastSequence、policyVersion、有效范围 |
| memory_message | messageId、sequence、sourceEventId、matterId、role、许可正文/工具结构、sourceRef、hash；追加幂等，不与业务正文双写 |
| memory_matter | ACTIVE/CLOSED/REOPENED、事项目标、结束证据、关联范围；不表达业务流程状态机 |
| memory_summary | summaryId/version、expectedRevision、coveredFrom/Through、完整工具组边界、structuredSummary、entityRefs、sourceHashes |
| memory_entity_anchor | scoped entityId、alias、sourceMessageRefs、briefDefinition、最后证据版本 |
| preference_block / preference_transition | context中的owner/subjectRef/scope/dimension唯一逻辑块、currentState；按证据追加时间挂点与临时例外 |
| memory_experience | 域/agent/owner、场景、证据、confidence、lastValidatedAt、反馈、policyVersion、active；不与硬规则表混用 |
| memory_shared_export / memory_share_grant | exportId、来源调用/正文hash、revision、获准payload、接收范围、有效期与撤回状态 |
| memory_processing_job | 评估/归并/索引工作游标、幂等身份、PROCESSING lease、bounded retry、失败状态 |

逐接入口盘点可靠事实源与事务边界；已有不可变输入/完成结果账本可确定重建交付事件时只记录引用，不复制业务正文；没有可恢复事实源才增加最小 journal/outbox。启用可靠记忆前必须验证输入接受前记录、调用接受时绑定交付许可、结果完成事实与待交付身份可恢复的顺序。副作用已完成但记忆交付失败只补交付，不重执工具；不能以 afterAgent 成功作为唯一可靠记录。无可靠材料时标记该消息未记录，禁止压缩移除；原始 streaming chunk 不逐块写 memory，只在完整许可消息/结果形成后交付，现有事件审计继续负责SSE。缓冲是短期交付机制，不是第二个长期真源；确认后按保留策略清理。不无条件要求九服务各建数据库/同名表。

memory 入站事务将真源变化、处理job与索引outbox原子记录；远程模型/索引在事务外执行。异步worker使用受管SERVICE凭据，只能处理已授权入站事件的精确ID/owner/domain，不保存用户token、不因用户租约到期自动冒充用户；读取仅限工作证据，撤回/删除检查后才提交结果，不能授予通用用户搜索权。

拒绝“压缩时第一次保存”和“直接把窗口saveAll覆盖完整档案”，它们分别存在崩溃丢历史和截断删除历史风险。

### D4. 工作集加载、并发与I/O预算

进入/冷恢复按contextKey加载摘要+近期消息+事项/版本及获准投影；缓存键包含owner/domain/agent与授权/policy版本，限定容量、TTL及session空闲回收。同run多个模型轮次本地复用，不挂远程memory Advisor每轮多次读写。

同conversation采用持久租约/乐观版本与单调消息sequence，分布式实例不能并发整体覆盖。一次执行记录采用的摘要与偏好版本；重新接入、对象/范围变化、明确纠正、撤回或授权失效时刷新/丢弃对应投影。变更通知只加速失效，丢通知时下一个事项/用户输入边界核验内容版本；恢复后、每次入模前及输出前必须重新核对当前身份和所有私有/共享来源授权，不能仅查用户token。摘要、锚点和缓存保留sourceVersion/grant依赖；撤回其中一个来源时相关派生内容整体失效或从剩余获准证据重建。checkpoint执行事实不删除，但其中旧正文不能因同owner恢复而自动进入可读工作集。当前授权验证可独立于完整正文装配，不要求每模型轮重新读全窗口。

失败区分：缺失匹配与依赖不可用不同；可选个人化装配失败允许继续当前明确请求但显式标记MEMORY_UNAVAILABLE/NOT_APPLIED，不能用已撤回内容；认证/授权失败始终拒绝。历史指代依赖缺失时说明不可获取，不猜测原对象。message journal失败不虚报已记录；压缩失败或未确认原文持久时不移除消息；触及硬上下文预算返回CONTEXT_BUDGET_EXCEEDED并保留恢复材料，不无限增长。

### D5. 双触发、安全压缩与事项重开

软token阈值、硬context预算、近期保留数量均配置；token估算器可替换并记录版本，不能把默认字符/4当精确tokenizer。soft阈值排队生成摘要，hard预算前必须有安全可用工作集。

事项结束触发来自用户明确确认或可核验的本次结果，生成matter closure证据；模型仅可提出结束候选。A2A完成“创建场次”不等于访谈结束。事项转移只裁剪已关闭事项，开放事项的关键材料继续保留，重开从摘要/引用恢复。

统一 CompactionCoordinator：冻结expectedRevision和压缩范围；确认档案持久覆盖；完整保留assistant tool-call/ToolResponse组以及审批/未知结果等执行事实；生成结构化摘要（目标/事实/决策/否定/例外/未决/实体/证据）；校验大小、来源与禁止内容；事务保存摘要及覆盖关系；CAS替换运行工作集。分别记录conversation revision、coveredThrough和run/checkpoint revision，不宣称跨库事务。摘要已提交但运行CAS失败或进程崩溃时，恢复重新核验摘要证据和授权，采用有效覆盖范围并合并所有未覆盖尾部，避免重复入模/丢消息。新输入或其他summary抢先提交时不能以旧候选覆盖当前窗口。模型超时/失败摘要不写成正常总结。压缩恢复不会更换run/skill/approval/call身份。

先针对实际解析的1.1.2.3制品验证Hook执行顺序、消息更新控制、失败传播与工具数据；只有可使替换等待上述可靠提交及版本核验的接法才能启用，不能以API存在代替安全集成测试。原生Hook提供token入口，但其未经保护的默认REPLACE不直接生效；必要时由薄扩展包装其触发/生成能力，而非另开无授权截断器。

原生hook作为token入口，事项闭合通过相同协调器进入，避免两个压缩器覆盖。背景摘要过程不阻塞SSE回调；只有实际达到模型输入硬预算时才需要在执行边界等待有界结果。

### D6. 历史补忆与索引

先看本地实体/事项锚点和近期消息；仅对历史指代、别名或事实缺口触发查询，不为任何新词默认检索。搜索按domain/conversation/matter强范围优先，明确请求与授权才扩大到本域跨会话，再到已获准export。

检索分两道核验：双路召回前施加当前用户/服务/域授权过滤；RAG将有界候选正文送入通用重排前，通过独立受管原生RPC向memory取得当前候选许可（recordId/revision/hash/owner/grant及派生source依赖），排除删除、撤回、失效版本及派生内容。该许可入口仅核验候选身份与授权，不返回正文、不执行搜索、不反向调用RAG，也不改变索引禁止回调获取业务正文的契约；调用无许可或不可用即失败关闭，重排仍只由RAG执行。许可绑定本批候选/请求/短有效期，在实际重排派发前核验，撤回已在该边界生效时未授权正文派发次数为0；已经合法派发的内容不能追溯收回，但后续输出仍必须因撤回排除。memory取得候选后仍再次查真源当前版本/删除/owner/grant，返回briefExplanation、事实/决定/否定、sourceRef/version和评分。无匹配不编造，语义相似不证明实体相同。注入独立memory data section，不解释为系统指令。来源为领域业务档案时只保存/检索获准投影与引用，不能将脱敏映射或逐字稿无限复制到memory。

新逻辑空间按注册域配置 memory.<domain>.session-v2 / preference-v2 / experience-v2，协作空间 memory.coordination-v2；初始化/集合不得由模型/用户请求自动创建。完整正文真源在SQL；RAG只存受控有界分片和评分授权元数据。

### D7. 异步偏好评估与单维度完整历程

PreferenceBlockKey=(ownerId, verifiedSubjectRef, canonicalScopeId, dimensionId)。私有canonicalScopeId包含domainId+agentId，不能只按domain归并；显式共享使用稳定授权组/共享范围身份，只有同一有效共享scope内才能归并。同owner、同域、同主体和维度的两个未共享Agent逻辑键不得碰撞。用户自身是默认主体；客户等其他主体仅在已有可信业务关联和读取授权时适用，模型不能猜subject。稳定维度注册（如commute_time/budget/floor/copy_length），不能“所有偏好”一个无限大blob，也不能每句话新键。

journal事件到memory后，异步先评估 PREFERENCE / TASK_CONSTRAINT / QUESTION / OTHER，附肯定/否定、长期修正/临时例外、证据引用与提取模型/规则版本。按同subject/scope/dimension微批次归并，处理游标和sourceEventId去重，assistant复述、摘要、检索命中不增加观察数。

块保存过去到现在时间挂点：occurredAt、observedAt、effectiveFrom/Until、明确修正关系、confidence、evidence与sourceSequence。sourceSequence仅排序同一conversation，不比较不同会话的局部序号；跨会话归并采用block revision/OCC、有效时间、明确被修正证据及可信因果关联。不能确定先后时保留冲突，不用worker完成时间或不可信客户端时间静默覆盖。用户明确纠正先更新活跃context，异步库尚未追平时本次指令覆盖持久旧默认。

RAG物理长分片共享blockId和revision；任一命中由memory归并返回一个块的currentState与完整可追溯历程，长历史在同blockId/revision下keyset分页，不丢旧值或伪称截断历程完整。prompt只注入当前适用值和关键例外/证据，不全量历程。最新索引分片集合随revision更新，旧分片tombstone，避免混版本聚合。

confidence是证据支持，activation是使用优先级，relevance是事项适配。时间权重读时确定asOf，基于有效观察/确认，不按检索次数刷新；v2不自动使用旧的每次减0.05。新事项起始作一次偏好装配，同事项复用；范围变化/纠正/撤回局部刷新。事项识别复用当前请求分析的结构化结果，不额外每轮调用一个大型模型或变成业务路由。

### D8. 经验与硬规则

硬规则仍来自当前受管代码/配置/政策；受权限的修改/停用沿原入口，不由模型学习或时间衰减改变。现有 system-constraints种子逐项审核来源/版本，旧workflow文字仍只是模型指引，不能恢复强业务步骤（LR-17/18）。

经验默认owner+domain/agent私有；人工/领域审核可发布独立非个人知识对象作为域共享经验，但脱敏标签不授予个人原证据读取权、共享对象不能反向泄漏个人来源正文；个人记录及证据仍owner-only。避免从个人内容自动生成跨用户规则。后台验证真实feedback/outcome，保存正负反馈和lastValidatedAt。评分使用versioned relevance × confidence × activation × timeDecay，并在去重后的token预算内选择；active=false/撤回立即排除。被使用不是验证，模型自评成功不是结果。时间衰减降低选择权重，不物理删除事实，保留复核/停用/修正历史。

### D9. A2A/SSE与记忆双轨

```
SubAgent许可结果
  ├─ displayPayload/progress + collaborationPayload
  │     → A2A → 当前Supervisor工作集 / 事件审计 → 前端SSE
  └─ 原文/获准协作导出journal
        → memory保存 → committedRef(version/hash)
        → 后续协作者授权详情/检索，RAG可稍后就绪
```

不将A2A改成引用-only；delta/status/final仍直接携带必要正文。current Supervisor 已收到payload无需重复read；大档案只携brief+ref，确有需要才读。displayPayload、collaborationPayload、durableExport分别白名单和大小预算，按需共享，私有history不因能在SSE展示一段进度而授权全域访问。

引用为定位数据不是auth beacon；auth材料沿既有专用transport，永不进入SSE/普通metadata。新增 memoryRefs/exportState/version 字段为增量兼容。commit前可带稳定sourceExportId及PENDING状态但不可宣称引用可读取；memory确认后经独立通知或后续查询提供COMMITTED，RAG状态单独INDEX_PENDING/INDEX_READY。memory失败不阻塞delta，不撤回子任务执行事实，不虚报归档成功。

来源域发布不可变export；其他Agent只有获准读取权，修改提议回来源域，禁止多人覆盖一个共享Map。grant绑定真实owner/matter/participant/purpose/current version；撤回检查涵盖内存投影、详情与检索。普通结果获得者只能在委托允许目的内使用，转交他域需另核授权。

既有生成成果保存仍在Observe可靠路径，artifact.created在成功回执后，tool/workflow完成保持既有时序；不能把偏好/索引异步性推广成所有成果终态后保存。Studio原生成成果及人工修订关系保留。SSE审计/seq回放不迁入memory，不在progress callback增加memory read/write/embedding/model调用。慢memory仅影响记忆就绪，不改变已有delta/终态展示到达顺序。

### D10. 兼容、观测与验收门

能力按domain feature flag关闭/影子/启用，所有启用Agent做接线测试；影子模式只记录评估、不改prompt和可见输出。历史owner未知或domain不明记录保持隔离，不按session名/agentId猜公开；旧v1 CRUD/RPC字段与空间保留受鉴权读，v2偏好块独立版本，不能回滚时从完整历程压成有损单值。

错误分类：AUTHENTICATION_FAILED、FORBIDDEN、VERSION_CONFLICT、MEMORY_UNAVAILABLE、CONTEXT_BUDGET_EXCEEDED及后台INVALID_EXTRACTION/RETRY_EXHAUSTED；job失败与会话业务成功分别观测，指标不暴露正文/token。

验收分别记录：首个可见SSE、A2A终态展示、成果已持久、记忆export可读、RAG可检索、Studio修订可读。固定模型桩/故障环境验证memory阻塞2秒或不可用期间delta回调新增memoryI/O次数为0、接收不等待该故障；2秒只是故障夹具参数。真实环境在相同模型、拓扑、负载与安全配置下比较改造前后P50/P95、RPC/DB次数与队列，据基线和容量证据记录启用预算，不预设无依据固定毫秒门限。认证/审计开销可单独归因但仍计入用户端到端延迟，不移除安全复验或扩大超时虚报通过。

## Risks / Trade-offs

- [异步持久与即时展示不同步] → 独立PENDING/COMMITTED/INDEX状态；最低可靠journal，引用只在真实回执后可读，成果沿原可靠路径。
- [summary丢关键工具结果或新的输入覆盖旧摘要] → 完整调用组、结构化证据、coveredThrough与CAS、失败不替换；原文始终可回查。
- [session热缓存跨用户/域泄漏] → 复合键、可信调用身份、当前授权与policy版本、撤回失效，不依赖namespace授权。
- [异步抽取旧事件覆盖新偏好] → 源sequence与effective挂点、幂等证据、明确修正/例外单独表达，同块归并。
- [完整历程无限增长与RAG分片误当多个偏好] → 唯一blockId/revision、受控物理分片、详情分页、prompt预算，旧分片失效。
- [后台工作扩大用户读取权] → 精确事件job范围与SERVICE只加工授权证据，当前删除/撤回复核，无user token持久化。
- [将所有memory加工改异步破坏Studio] → 现有artifact持久/终态时序不变，独立记忆job，专项回归KI-24的成果/事件部分成功窗口；KI-114的Studio领域动作仍不声明已修。
- [当前SSE严格taskId过滤、delta限流/审计降级干扰观测] → 先固定真实消费者入口；父子run订阅专项回归与增量关联处理，不进行全仓事件架构重写。
- [新外部HTTP/凭据安全] → 新请求仅受管http/https且发出前验证host与实际地址，拒绝localhost/环回/私有/保留地址；不扩展旧transport授权。外部输入SQL全部绑定；secret只ENV/密钥服务，样例/测试不写可用凭据。已有受管RPC身份链继续遵守LR-33/41。

## Migration Plan

1. 盘点当前九个SubAgent源码接入口，再核对目标环境实际注册/启用集合、SSE入口/基线、数据/schema、LR/KI及Studio迁移实际状态；不把源码状态当已部署。
2. common/薄适配契约与失败关闭先行，新增版本化迁移与清单checksum；只在缺可靠事实源的接入口新增必要journal结构，不改已应用SQL字节，不盲重种子。
3. memory真源、交付/job、授权及新RAG空间在生产dispatcher暂停下部署；按owner/domain可信来源盘点历史，未知不回填公开空间。空间由授权运维显式初始化。
4. 分离入站持久、影子加工、生产加工、索引派发和prompt读取开关；影子worker验证消息顺序、压缩摘要、偏好归并与经验分类，但不改变生产prompt/投影或可见输出。验收后逐域启用Supervisor/marketing/interview治理面；其余Agent完成接线和可靠来源验收后启用，访谈运行面不换执行器。
5. 接A2A增量payload/ref与协作grants，验证现有前端content/result字段、父子事件、回放和Studio成果；共享读与SSE分别故障注入，无真实前端源码/实例则明确记录未完成浏览器验收。
6. 有界开发切流、索引水位与删除/撤回验收后打开生产加工/索引派发；留存旧schema/制品/配置与新真源，不宣称生产排空。
7. 回滚先停止新记忆入口和新prompt消费，保留必要交付接收与worker直到有界排空；依赖不可用时保留未确认journal及job并报告未排空，最后停生产加工/投影。保留v2历史与当前授权边界，回兼容制品/配置而非删除新表/集合；旧单值接口不覆盖v2历程，已接受A2A任务按原关联处理。
