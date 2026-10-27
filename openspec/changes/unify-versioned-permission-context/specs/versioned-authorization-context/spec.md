## Purpose

定义平台用户操作权限、可信运行上下文和跨服务委托的统一行为，使授权集合能够由中心服务维护、在运行内安全复用并在变更后有界失效，同时不把缓存、模型参数或业务管理员身份变成个人资源访问的旁路。

## ADDED Requirements

### Requirement: Restricted operations use registered permissions and role-derived grants

系统 SHALL 只为默认不向所有有效登录用户开放、必须显式授予的受限拦截点登记唯一权限码；普遍开放的基础操作不要求权限码，但 MUST 使用有效身份上下文，并在适用时验证资源owner/参与关系和业务状态。普通用户对受限操作的有效权限 SHALL 来自其有效角色的权限并集，用户与角色支持多对多关联及有效期。业务管理员 SHALL 不依赖角色取得全部当前登记的受限权限；受限拦截点引用不存在、停用或未授予权限 MUST 被拒绝。业务管理员全权限 MUST NOT 绕过资源范围、个人 Memory owner 规则、业务状态、参数校验、审批或幂等。授权管理操作 MUST 经过独立管理权限及审计，普通用户不得自行变更管理员身份或角色授权。

#### Scenario: Universal baseline operation requires identity but no permission code
- **WHEN** 已认证用户调用产品明确普遍开放的基础操作，且请求上下文有效
- **THEN** 系统不要求该操作出现在权限码目录或角色授予中；涉及资源时仍验证可信actor与owner/参与关系，涉及状态转换时仍验证业务状态

#### Scenario: User holds two role bundles
- **WHEN** 普通用户持有两个有效角色，其中部分权限重复且一项角色授予已经到期
- **THEN** 系统返回当前有效权限的去重并集，不返回到期授予，不赋予业务管理员范围

#### Scenario: Restricted gate references an unknown or inactive permission
- **WHEN** 受限拦截点引用未登记、停用或未授予的权限码
- **THEN** 系统拒绝该操作，不根据权限名后缀或通配身份放行

#### Scenario: Administrative privilege does not expose personal memory
- **WHEN** 业务管理员具有所有操作权限但请求另一用户的个人 Agent 工作集或偏好
- **THEN** 领域拒绝访问，不将全权限解释为跨 owner 授权

### Requirement: Auth-owned business identity mapping is versioned and signed

Auth SHALL maintain explicit, typed mappings from a platform actor to business identity identifiers; the first adopted mapping is userId to brokerId. Mapping type and identifier SHALL be persisted in Auth, uniquely assigned to at most one active user for that type, and changed only through an audited authorization-management operation. The effective mapping SHALL be included in the Auth-signed snapshot and request context so Gateway, Supervisor, A2A and domain consumers can verify it locally without resolving an identifier from model arguments or making an Auth call for every tool. A mapping change SHALL increment permissionRevision and publish the normal authorization outbox event in the same transaction. Consumers MUST use only the exact identity type required by the target resource and SHALL still check the resource's owner, state and domain relationship. businessAdmin grants business operations but MUST NOT fabricate a broker identity or bypass personal Memory ownership.

#### Scenario: Broker attempts to replace the mapped identity in tool input
- **WHEN** the signed context maps the actor to brokerId 81 but a tool argument or request body supplies brokerId 82
- **THEN** the domain uses the signed mapping or rejects the mismatch before reading or changing the resource

#### Scenario: Administrator changes the broker mapping during a run
- **WHEN** an audited Auth update replaces the user's broker mapping
- **THEN** Auth increments permissionRevision and publishes invalidation; old snapshots remain bounded by the existing freshness and hard-deadline rules, and a refresh cannot replace the identity inside an already-bound run

#### Scenario: Business administrator has no broker mapping
- **WHEN** a business administrator with no BROKER mapping requests a broker-owned resource
- **THEN** the domain may use its explicitly permitted business-wide scope but MUST NOT invent a brokerId or weaken personal Memory owner checks

### Requirement: Shared versioned authorization and isolated execution identity

系统 SHALL 为经认证用户提供受限操作的完整有效权限快照及单调授权版本；普遍基础操作不要求进入该权限集合。多个同用户同版本运行 SHALL 复用该快照，不按每个运行复制分布式权限副本。运行身份 MUST 绑定真实 actor、认证有效期、原 session/run 和请求政策；身份与受限权限 MUST 只经专用服务端上下文传播，不从 LLM、普通 metadata、userId 或 checkpoint 恢复凭据。授权版本变化 SHALL 不改变原 actor；角色增加也不得突破原请求政策、已绑定审批或服务委托范围。

#### Scenario: Parallel sessions use one user permission version
- **WHEN** 同一用户在不同 session 中并行运行，且授权版本相同
- **THEN** 操作权限快照可共享，消息、任务、凭据有效性和数据访问范围仍按各自身份关联隔离

#### Scenario: Model claims another actor or administrator
- **WHEN** 模型工具参数声明其他 userId、管理员标记或权限集合
- **THEN** 系统拒绝其身份或授权含义；合法业务目标参数仅经过对应领域资源授权后使用

#### Scenario: Restart has identifiers but no valid credential
- **WHEN** 重启恢复仅取得 actor/session/run 和旧版本号
- **THEN** 系统要求原用户重新认证，不把这些定位字段解释为当前可用授权或自动签发新凭据

### Requirement: Ordered authorization publication and bounded invalidation

授权真源 MUST 持久可审计。用户角色、角色权限、管理员身份、账号资格、权限目录或授予期限变化 SHALL 产生单调版本和可恢复发布事实。中心授权服务 SHALL 先发布完整新快照及当前版本，再通知消费者；重复或乱序发布 MUST NOT 回退版本。消费者 SHALL 处理主动失效通知、重规划版本核对和活跃用户去重后的有界对账。第一阶段 SHALL 允许自权限变更提交至消费者得知变更前最多30秒的兼容窗口，且不得越过凭据或快照硬到期；已经得知撤回后 MUST 禁止启动被撤回或尚无法核清的操作。

#### Scenario: Database commit succeeds while publication is unavailable
- **WHEN** 授权真源已提交但分布式快照或通知暂时不可用
- **THEN** 系统保留待发布事件并有界重试，管理响应如实标记发布未确认，不宣称所有消费者已经生效

#### Scenario: Notification is duplicated or out of order
- **WHEN** 消费者先获知新版本，后收到旧版本或重复通知
- **THEN** 消费者不恢复旧授权，也不重复刷新相同版本

#### Scenario: Notification is lost during a long execution
- **WHEN** 活跃运行没有返回规划阶段且丢失了权限变更通知
- **THEN** 有界对账在兼容窗口内发现新版本；无法维持规定新鲜度时停止新受保护动作，不无限沿用旧快照

#### Scenario: A stale authority check is published late
- **WHEN** 一个旧版本权威核验在授权变更后迟到发布
- **THEN** 证明绑定原版本、状态、权限hash和真实核验时刻，不能覆盖新版本或把兼容期限从晚发布时刻重新计算

#### Scenario: Same version is rebuilt after cache eviction
- **WHEN** 相同授权版本的缓存内容被回收后重建
- **THEN** 稳定权限内容和hash不变，新鲜证明可重新签发但不延长原run或父委托截止

#### Scenario: Permission grant expires without another write
- **WHEN** 角色授予到达有效截止时刻而没有新的用户请求或管理写入
- **THEN** 中心服务发布失效版本并通知，消费者同时受快照有效期限约束，不能等待未来访问才发现永久陈旧授权

### Requirement: Boundary validation and planning version checks

每个新外部请求 SHALL 在可信入口完成一次认证及完整身份/受限授权获取，首次规划不重复获取。Supervisor重新进入业务Model规划时 SHALL 核对当前授权和认证状态版本，同版且有效时不重复计算或下载权限。稳定已绑定Agent run上的A2A和已接入MCP `tools/call` SHALL 携带Auth签名父context、当前freshness和Agent服务逐请求proof；请求proof绑定实际序列化JSON-RPC method/id/body及可信配置中的稳定动作标识，受限动作另绑定精确权限码。接收服务SHALL在任务/工具分发前本地验证身份、签名、动作分类、适用的受限权限、目标和期限，并按本地短期重放保护消费nonce；普遍基础动作不要求权限码，但仍须有效用户上下文和目标域owner/状态校验。Auth SHALL 仅在Supervisor规划刷新边界联系，MCP逐工具调用SHALL不增加Auth/Redis往返。外部MCP不支持用户委托协议时，仅可通过可信清单显式开放无私有数据能力，且不得接收用户身份字段。任何同一次执行内部节点、工具、模型和流事件SHALL使用本地有效上下文判断，不逐节点、逐工具或逐事件远程复验。目标、参数、服务身份和请求重放检查仍MUST在相应执行入口进行；跨实例owner/nonce持久化由各协议的具体持久任务要求覆盖。

#### Scenario: A tool result triggers replanning without authorization change
- **WHEN** Supervisor 获取工具结果后重新进入规划且权限和认证版本未变
- **THEN** 只进行轻量版本核对，复用原有效集合，不重复全量 Auth 获取

#### Scenario: Several tools execute within one accepted domain invocation
- **WHEN** 子 Agent 已完成入口验证并在同一次执行内多次调用工具
- **THEN** 工具本地核对许可和领域资源条件，新增远程用户认证次数为零；新的外部子调用仍有自己的边界验证

#### Scenario: A2A request reuses a signed run context without an Auth round trip
- **WHEN** Supervisor 在有效且未 DIRTY 的已绑定 run 中向子 Agent 发送或控制一个 A2A task
- **THEN** Agent 为实际 JSON-RPC body 生成新的短期绑定和服务 proof，接收端本地验证 Auth context/freshness 签名、caller、audience、操作、request/task/body/nonce 与 owner；该 A2A 请求新增 Auth RPC 为零，模型传入的用户身份字段不参与 actor 选择

#### Scenario: MCP call reuses the signed run context without per-tool Auth checks
- **WHEN** Supervisor 在有效且未 DIRTY 的已绑定 run 中调用已登记的第一方 MCP 工具
- **THEN** 专用transport为实际`tools/call`生成模型不可见的短期body绑定proof，MCP服务本地验证Auth context/freshness和精确server+tool操作、caller、actor、run、nonce及期限；该工具调用新增Auth和Redis往返均为零，外部无用户委托能力仅在可信清单明确标记无私有数据时可见

#### Scenario: Unauthenticated direct service call
- **WHEN** 调用者绕过 Supervisor 直接提交普通 userId 或不可信身份头
- **THEN** 目标业务入口拒绝访问，不将调用来自内网或服务名作为用户授权证明

### Requirement: Request-bound service delegation cannot expand authority

跨服务授权 SHALL 同时约束真实用户、受信调用服务、接收服务、稳定动作、请求内容、任务关联和有效期，并防止篡改和重放。受限动作还必须包含并验证显式获准的权限码；基础动作不通过权限码扩权，仍按签名身份、资源关系和状态执行。业务域 SHALL 独立验证后只接受其获准能力和受限权限子集；子上下文截止 MUST 不超过父上下文、原run固定截止和当前授予期限，同版刷新或重新签发不得重置原run截止。普通服务身份 MUST NOT 自动取得所有用户权限；缺用户上下文不得自动转换为 SERVICE 模式。后台 SERVICE SHALL 只使用已接受精确任务、许可或域治理职责，不获得通用个人读取权限。授权材料 MUST NOT 出现在模型消息、工具业务参数、URL、SSE、业务结果、长期记忆或日志中。

#### Scenario: Caller changes a target or tool argument after delegation
- **WHEN** 有效授权引用被用于不同目标、请求正文或 task
- **THEN** 接收方在执行前拒绝，不因用户确实拥有该权限而放行被篡改请求

#### Scenario: Approved caller exceeds the user's permission set
- **WHEN** 已登记服务请求超出用户权限、本次请求政策或受限委托的操作
- **THEN** 中心和执行边界拒绝，不以服务公钥已登记为全业务放行依据

#### Scenario: A consumer attempts to publish authorization notifications
- **WHEN** 业务消费者使用自己的分布式缓存账号修改权限key、延长TTL或发布通知channel
- **THEN** 权限拒绝，只有Auth可写与发布；消费者只读精确namespace并订阅指定channel

#### Scenario: Background delivery after user credential expiry
- **WHEN** 已可靠接受的精确结果事件待补交付但用户凭据已到期
- **THEN** 受限 SERVICE 可按原许可补交付，不换发用户读取凭据，删除和来源撤回仍生效

### Requirement: Execution binding is independent and can only narrow ingress authority

系统 SHALL 提供独立执行绑定操作，将尚未绑定run/session的可信入口授权关联到经Supervisor持久归属和幂等核查的真实执行。绑定不重新认证用户或扩大权限，必须验证原入口授权、当前版本/epoch/状态、受权服务签名、本次请求和原入口关联；只允许一次确定的actor/caller/run/session/执行输入/范围tuple。合法同tuple重试可幂等返回，不同tuple拒绝，不延长原context或run硬期限。已绑定context不能通过该操作换执行，刷新接口也不能替代绑定。绑定成功前不得进入业务执行或捕获执行权限，业务owner核查仍属Supervisor/领域责任。

#### Scenario: Supervisor resolves the actual session after Gateway admission
- **WHEN** Gateway已验证请求但入口run/session为空，Supervisor核实持久归属后确定执行关联
- **THEN** 独立绑定返回Auth签署的非空执行context，保留原actor/scope上限/policy和hard截止，不修改原入口签名

#### Scenario: The same ingress is bound to another execution
- **WHEN** 原入口证明已绑定后被用于另一run、session、输入或许可范围
- **THEN** 绑定记录及请求证明拒绝冲突，零业务副作用，不把入口身份当无限任务签发权

#### Scenario: A valid binding request is retried
- **WHEN** 同入口、同actor/执行tuple使用新的受权请求nonce重试
- **THEN** 可幂等确认原关联，期限保持原值；重放同nonce仍被拒绝

### Requirement: Same-run refresh is independent and cannot renew authority boundaries

系统 SHALL 提供独立同运行刷新操作，验证真实有效access凭据、原Auth签名context和受权调用服务证明；可以接受旧permissionRevision以更新授权，但原credentialEpoch必须当前有效，原context和run硬期限不得已到期。新请求必须绑定原actor/run/session/audience/policy以及本次requestId/nonce/body；返回当前完整snapshot及原许可与当前权限的交集，不增加范围或延长任何原截止。刷新成功的签名响应 SHALL 可被原本地运行接受；子域仅持委托或userId时不能自行换发，必须经原可信用户入口处理。注销等epoch失效必须要求新的认证续接，不以版本刷新绕过。首次授权、委托和刷新证明不得跨操作或请求复用。

#### Scenario: Permission revision changes within a running request
- **WHEN** T0建立运行后T0+10权限版本变化，原context未到期且真实access和credentialEpoch仍有效
- **THEN** 独立刷新返回最新版本并可被原运行接受，hard截止仍为原T0+300或更早期限，不产生新run或副作用

#### Scenario: Refresh must not restore a larger permission scope
- **WHEN** 用户原范围部分撤回并增加其他权限，或普通用户后来成为管理员
- **THEN** 刷新scope只包含原签名范围与当前权限交集，不自动恢复未授予的动作或绕过原审批

#### Scenario: A credential invalidation is not a permission refresh
- **WHEN** 原credentialEpoch因注销或账号级撤销变化，或access/context硬期限到达
- **THEN** 刷新被拒，要求原用户新的认证续接，不凭旧context/userId延长运行

#### Scenario: Refresh changes the original execution identity
- **WHEN** 刷新请求更换actor、run、session、audience、policy或伪造原context签名
- **THEN** 在签发新context前拒绝，原状态和领域副作用不变化

#### Scenario: Refresh proof is replayed or reused from another RPC operation
- **WHEN** 调用者重放同nonce或用首次授权/委托的证明发起刷新
- **THEN** 独立协议类型、操作ACL及持久防重放拒绝，不重新授权

### Requirement: Explicit failure and safe compatibility

首次认证、首次域验证、未知版本、已知撤回或超出新鲜度/硬有效期无法核验时 MUST 失败关闭，且区分401身份无效、403无权限、409版本或上下文冲突、503授权依赖不可用。暂时依赖故障时仅已验证且未收到失效的上下文 SHALL 在规定兼容窗口内继续。授权失败 MUST NOT 自动换用户、提权、切匿名或重试未知副作用；未执行的无权限工具可作为结构化结果返回模型选择其他获准动作。旧协议兼容 MUST 同等受资源与期限约束，且新旧验证路径不会形成较弱回退。

#### Scenario: Local snapshot exists during a brief cache outage
- **WHEN** 有效上下文已建立、没有已知失效且最近成功核对仍在30秒内，分布式缓存短暂不可用
- **THEN** 在原有效期和许可范围内继续；超过该窗口暂停新受保护动作并返回明确依赖错误

#### Scenario: Model selects a forbidden tool
- **WHEN** 模型选择未获准工具且动作尚未开始
- **THEN** 返回可关联的权限拒绝结果，不产生副作用、不提升权限，允许在剩余获准能力内重新规划
