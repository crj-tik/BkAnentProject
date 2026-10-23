# Agent Memory Runtime

## Purpose

定义 Supervisor 与所有官方领域 Agent 在可信身份与当前授权下必须接入并管理会话热工作集的行为，包括跨模型调用复用、事项起始与继续、局部刷新、并发版本和重启恢复。压缩、偏好/经验加工及 RAG 投影是附加能力；它们的开关不能关闭基础记忆读取、授权复核或本轮归档，也不能改变既有业务路由、审批及执行事实。

## ADDED Requirements

### Requirement: Trusted owner and domain agent context identity

系统 SHALL 将每份工作集绑定到经认证的 owner、可信 domain、可信 agent 和已验证归属的 conversation/context 组合身份；准入条件 MUST 同时满足 owner 归属与 domain、agent、context 范围授权，任何一个条件不满足均不得访问。domain 和 agent SHALL 来源于可信能力映射及经验证的调用身份，不得由模型或普通请求字段自行授予。事项、执行、远端任务和领域会话的关联标识 MUST 保持各自语义，不得相互替代身份或权限证明。

#### Scenario: Same owner does not imply domain or agent access
- **WHEN** 同一已认证 owner 下的另一个 domain 或 agent 请求读取未向其授权的工作集
- **THEN** 系统拒绝该访问，不因 owner 相同而共享工作集

#### Scenario: Forged scope cannot change effective identity
- **WHEN** 模型输出或普通请求字段声称另一个 owner、domain、agent 或 conversation/context
- **THEN** 系统不采纳其扩权含义，仅允许与可信身份及已验证会话归属一致的访问，否则拒绝

#### Scenario: Context association is not an authorization grant
- **WHEN** 请求仅携带已知的 A2A contextId、事项标识、任务标识、稳定引用、namespace 或 category
- **THEN** 系统仍要求完整可信身份及当前授权，不能仅凭这些关联或寻址数据读取记忆

### Requirement: Explicit sharing and current authorization

系统 SHALL 默认将工作集及记忆投影限定为 owner 下的域与 Agent 私有内容；域内组共享、事项共享或会话共享 MUST 有明确有效的授权，并限定来源、接收 domain/agent、用途、关联事项或会话、版本与有效期。详情、检索、导出、回放、后台加工及缓存内容使用 MUST 满足当前授权交集；管理员身份、已收到的一段展示内容或 Supervisor 身份不得扩大个人记忆读取范围。

#### Scenario: Explicit sharing permits only named participants and purpose
- **WHEN** 当前 owner 授予特定事项内的内容给指定接收 domain/agent 和用途
- **THEN** 系统仅向满足该授权的参与者提供获准内容，不开放来源域的全部历史，也不允许未经授权的再次转交

#### Scenario: Revocation invalidates cached use
- **WHEN** 工作集含有的共享内容已撤回、授权已过期或接收者不再获准
- **THEN** 系统禁止继续读取、注入或输出该内容，并使对应缓存投影失效，不以缓存命中绕过当前授权

#### Scenario: Administrative or supervisory role cannot cross owners
- **WHEN** 管理员或 Supervisor 请求另一 owner 的个人工作集或记忆
- **THEN** 系统拒绝该访问，不将角色或编排职责解释为跨 owner 的授权

### Requirement: Hot working set reuse across model calls

系统 SHALL 在相同可信 context 和有效版本下复用本地热工作集，覆盖同一执行内的多个模型轮次及模型切换；不得因每次模型调用重复远程装配相同历史或重复注入累计消息。冷启动、恢复和已确认的版本失效 SHALL 加载当前获准摘要、近期消息、事项及相关投影。缓存 MUST 有有界容量、有效期和空闲回收，并按完整身份及授权、策略版本隔离；模型变化不得改变记忆身份或访问范围。

#### Scenario: Repeated model rounds reuse a valid working set
- **WHEN** 同一执行连续调用模型且 context、授权、策略和相关内容版本未变化
- **THEN** 系统复用热工作集，不为每轮重复远程读取同一会话窗口，也不重复注入已存在的消息

#### Scenario: Model change preserves logical memory
- **WHEN** 同一 context 内切换模型且相关记忆与授权仍有效
- **THEN** 系统保留相同的获准逻辑工作集，不因模型变化创建新的 owner/context 或重新读取未变化的历史，并按目标模型的输入预算重新核验可用性

#### Scenario: Cold entry uses authorized current material
- **WHEN** 本地工作集不存在、已过期或已被回收
- **THEN** 系统装配该可信 context 下当前有效的摘要、近期消息、事项和获准投影，不使用其他身份的热缓存代替

### Requirement: Matter start and continuation assembly

系统 SHALL 在新事项开始时按当前目标、可信主体和适用范围装配相关偏好与经验，并记录本次采用的版本；同事项继续时 SHALL 复用仍有效的装配，不因每个模型轮次重新开展全量选择。事项身份 MUST 只管理上下文生命周期，不能授权业务调用或成为业务步骤调度规则；事项识别不得要求每轮额外进行独立的大模型判断。

#### Scenario: New matter receives relevant bounded context
- **WHEN** 用户开始一个与当前事项不同的新事项
- **THEN** 系统按该事项和当前授权提供预算内适用的偏好与经验，保留采用版本，不无差别加载全部个人历史

#### Scenario: Continuing the same matter reuses its assembly
- **WHEN** 用户补充同一事项且目标、主体、范围和相关版本均未变化
- **THEN** 系统继续使用当前事项的有效装配，不重复全量偏好或经验读取

#### Scenario: Matter label cannot dictate a business next step
- **WHEN** 事项标签或事项摘要暗示一个业务步骤顺序或下一调用
- **THEN** 系统将其作为上下文资料，而不据此自动路由、触发业务调用或判定必须完成的业务步骤

### Requirement: Local refresh and explicit correction precedence

系统 SHALL 在主体或范围变化、用户明确纠正、撤回及相关授权或策略失效时，仅刷新或移除受影响的投影，并保持未受影响的有效材料。用户当前明确修正或临时例外 MUST 在当前适用上下文中优先于尚未更新的持久旧默认；撤回或禁止使用的内容不得因异步处理未完成而继续生效。变更通知缺失时，系统 SHALL 在下一事项或用户输入边界核验相关版本，且任何实际读取、使用或输出均不得绕过当前身份与授权核验。

#### Scenario: Explicit correction takes effect before background convergence
- **WHEN** 用户明确纠正当前事项的适用偏好，而持久偏好仍是旧值
- **THEN** 当前上下文采用该修正及其适用范围，旧默认不覆盖本次明确指令，其他未受影响的材料保持有效

#### Scenario: Scope change refreshes only affected projections
- **WHEN** 当前事项切换可信关联主体或适用范围
- **THEN** 系统移除或刷新不再适用的偏好、经验和共享投影，不将旧主体材料沿用到新范围

#### Scenario: Missed notification cannot preserve stale access
- **WHEN** 内容或授权版本已改变，但本地未收到变更通知
- **THEN** 系统在下一事项或用户输入边界发现并刷新内容版本，且在任何实际使用或输出前拒绝当前已失效的授权内容

### Requirement: Memory lifecycle preserves routing and execution recovery

系统 MUST 保持现有业务状态机、模型调用选择权、能力范围、审批门和执行事实的权威边界。记忆加载、事项结束或重开、压缩及恢复 SHALL 只改变获准上下文，不得恢复已退役业务流程、按提示自动交接或在记忆内另立业务状态真源。恢复 MUST 保留原执行、技能版本、审批绑定、调用及已接受远端任务身份；摘要不得证明审批已完成、调用可重试或未知结果已成功。

#### Scenario: Approval waiting survives memory lifecycle changes
- **WHEN** 执行仍待用户审批时发生记忆加载、压缩、事项重开或重启恢复
- **THEN** 系统保留原审批绑定及待审批事实，不将摘要中的建议或历史批准作为当前调用授权

#### Scenario: Unknown side effect remains unknown
- **WHEN** 已提交调用的结果未知，而记忆含有预期结果或类似的历史成功
- **THEN** 系统仍保留原未知结果及远端关联，不自动重发、替换调用身份或宣称完成

#### Scenario: Interview governance memory cannot replace runtime authority
- **WHEN** 访谈治理面获得事项摘要或创建场次的完成结果
- **THEN** 系统不据此推进访谈运行状态、替换运行管线或推断访谈已结束，仍由原领域权威决定业务状态

### Requirement: Concurrent versions and isolated working set updates

系统 SHALL 维护会话的单调消息顺序、持久内容修订号及摘要、偏好等采用版本，并保证并发执行不能无条件整体覆盖彼此工作集或历史。工作集变更 MUST 仅在预期版本仍有效且执行持有当前更新权时生效；版本冲突 SHALL 可辨识，并保留已可靠记录的新消息及执行事实。迟到执行、过期更新权和旧授权版本不得覆盖较新状态。

#### Scenario: Concurrent input defeats a stale replacement
- **WHEN** 一个执行准备替换工作集期间，另一个合法执行已记录新输入并更新会话版本
- **THEN** 旧替换不能覆盖新输入，系统报告或处理版本冲突，并保留新消息及其顺序

#### Scenario: Late execution cannot reclaim newer ownership
- **WHEN** 失去更新权的执行在新执行接管后提交旧版本结果
- **THEN** 系统拒绝其对当前工作集的覆盖，不改变新执行的身份或已确认事实

#### Scenario: Adopted versions remain identifiable
- **WHEN** 两次执行分别采用不同摘要或偏好版本
- **THEN** 系统能区分各次执行实际采用的版本，不将后来的版本反写为早先执行所见内容

### Requirement: Restart recovery and credential separation

系统 SHALL 在重启或冷恢复后依据已可靠记录的会话材料和原执行事实重建获准工作集，恢复未确认的增量交付而不重复增加消息。重建的 context MUST 重新验证当前 owner、domain、agent 和每份来源授权；包含已撤回来源的派生摘要及锚点必须失效或从剩余获准证据重建，checkpoint 中的执行事实不得自动成为可入模的获准正文。持久化的 owner/context/任务标识不得代替重新认证。真实用户凭据、认证信标及身份租约 MUST 不进入记忆、摘要、普通 metadata 或执行快照；重启后不得从身份标识自动签发或还原用户凭据。

#### Scenario: Restart reconstructs memory without duplicate delivery
- **WHEN** 进程在消息可靠记录后、远程记忆交付确认前重启
- **THEN** 系统在当前认证和授权成立时恢复原 context 并继续同一消息的交付，不重复追加，也不丢弃原已记录消息

#### Scenario: Durable delivery is distinct from resumed user access
- **WHEN** 已获准且可靠记录的事件在用户租约失效后仍待交付
- **THEN** 服务仅凭当前服务认证及可核验精确事件接收事实补交付，不重放用户信标、不取得通用用户搜索权；用户恢复读取仍需原 actor 重新认证，删除或撤回策略仍被执行

#### Scenario: Expired credentials require authentication again
- **WHEN** 恢复材料保留原 owner 和执行标识，但当前用户凭据不存在或已失效
- **THEN** 系统要求重新认证，不从记忆或执行快照中的标识推导可用凭据

#### Scenario: Recovery preserves accepted task association
- **WHEN** 重启前远端任务已被接受且原关联已可靠记录
- **THEN** 恢复继续使用原任务及调用关联，不因重新装配记忆而新建替代任务或更换执行身份

### Requirement: Core memory is mandatory; auxiliary enrichment is isolated

系统 SHALL 要求 Supervisor 与所有官方 A2A Agent 在调用模型或业务工具前，完成当前 owner/domain/agent/context 绑定、Memory 工作集读取和当前授权/版本核验；成功完成的本轮消息 SHALL 通过受许可的归档交付持久化。缺少 Memory 接线、服务不可用、身份无效、版本冲突或授权无法核验时 MUST 在模型/工具执行前失败关闭，不得由配置开关或旧业务路径绕过。查询成功但无匹配是有效的空工作集，可继续不依赖缺失历史的请求；请求依赖的历史对象不可获取时 MUST 说明缺口并澄清。压缩、偏好/经验加工及 RAG 投影等附加能力不可用时，可在本轮基础记忆仍成功且请求独立的前提下标记未应用并继续；不得把附加能力未就绪解释为整个 Memory 可选。

#### Scenario: Core Memory is unavailable before Agent execution
- **WHEN** Supervisor 或官方 A2A Agent 无法读取当前工作集、验证身份/授权或核验版本
- **THEN** 系统在任何模型调用和业务工具调用前拒绝本轮执行，不走无记忆旁路，并保留可诊断的失败状态

#### Scenario: Empty working set is not a dependency failure
- **WHEN** Memory 读取与授权校验成功，但当前 context 没有匹配的历史材料
- **THEN** 系统按有效空工作集处理；独立请求可以执行，依赖历史对象的请求先向用户澄清，不猜测缺失内容

#### Scenario: Auxiliary enrichment failure does not disable core memory
- **WHEN** 基础工作集已成功读取和授权，但偏好/经验加工、压缩或 RAG 投影不可用
- **THEN** 系统保留基础记忆和安全边界，按该附加能力记录未应用状态；不关闭其他基础记忆操作，也不注入未核验内容

#### Scenario: No match remains different from dependency unavailability
- **WHEN** 记忆查询成功但没有匹配的获准内容
- **THEN** 系统表示无匹配，不将其报告为 MEMORY_UNAVAILABLE 或虚构个人化依据

#### Scenario: Authorization denial cannot become degraded success
- **WHEN** 记忆入口或其依赖返回认证失败、权限拒绝，或当前私有内容授权无法核验
- **THEN** 系统拒绝受保护访问，不以无记忆旁路、管理员身份或热缓存继续使用该内容

#### Scenario: Required historical referent is unavailable
- **WHEN** 用户请求依赖某个历史对象，但该对象的获准证据因缺失或依赖故障不可获取
- **THEN** 系统说明无法确认原对象并请求必要澄清，不用相似对象或模型猜测填补缺口

### Requirement: Memory work does not serialize live collaboration delivery

系统 SHALL 保持获准的协作正文、进度和本次结构化结果直接用于当前推理及既有实时事件链；逐 delta 或 progress 交付 MUST 不新增记忆读取、写入、索引或模型加工依赖，也不得要求当前已收到的正文先持久化再回取。长期记忆就绪、检索就绪与业务成果已持久 SHALL 分别表达，记忆失败不得改写执行完成事实或破坏原成果保存、终态与人工修订的来源及先后约束。

#### Scenario: Slow memory does not delay direct event delivery
- **WHEN** 记忆持久化或索引依赖缓慢或不可用，而协作正文及进度已获准交付
- **THEN** 实时链直接交付原获准内容，不在逐 delta 或 progress 路径等待新增记忆操作，也不重复回取当前已收到的正文

#### Scenario: Durable memory readiness is reported separately
- **WHEN** 业务调用已完成而记忆记录尚未确认或索引尚未就绪
- **THEN** 系统分别表达执行完成、记忆待确认及检索状态，不虚报记忆引用可读，也不撤销既有执行事实

#### Scenario: Optional processing cannot weaken artifact persistence ordering
- **WHEN** 偏好、经验或索引加工尚未完成，而业务生成成果需要按既有规则保存并发布成果事件
- **THEN** 系统保持成果保存成功后才发布对应成果事件的原约束，并保留生成成果与人工修订的原来源关系
