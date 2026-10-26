## Context

动机与范围见 proposal.md。本设计对应九份差量规范，只形成可评审的实施方案，不代表代码、部署或权限迁移已经完成。

已核源码基线为 `dbc13db`：Auth 一般 principal 只有userId，权限判定混用精确码与前后缀；Gateway一次解析后Agent入口继续重复解析；Graph每节点、目录、工具和A2A输出存在真实Auth往返。Auth/Agent已有Redis依赖，但权限缓存、变更outbox和广播尚未接线；A2A beacon是Auth进程内Map。服务Ed25519密钥原语可以复用，LR-98要求协议标签隔离，独立RAG HMAC不能一起删除。

Interviewer已完整审阅51个main Java、4资源、10测试及3迁移；82项既有测试通过，不证明跨用户授权。实际存在：工号为模型/请求参数、统一场次票不绑定用户职责、开台工具携票入模/归档而终态sanitizer删票、题单更新未守全部状态、case/asset关联未查、MCP报告未复核L2/L3源、上传未真正脱敏。Memory已有owner/domain/agent/context、来源/grant和SERVICE精确交付边界；这些资源事实不能用Auth操作集合替代。

## Goals / Non-Goals

**Goals:**
- 单次可信入口获得完整有效操作集合，普通执行不重复调用Auth；Model重规划只核版本。
- 消费者可独立验证委托，不相信模型或普通身份头；新业务执行边界一次验证，内部复用。
- MySQL是授权真源、Auth是Redis权限唯一writer；内存引用共享用户版本而非每run复制Redis集合。
- 兼容窗口有界且可观测，授权/凭据失效与资源撤回分开；多会话、多实例和恢复保持原actor/执行事实。
- 首批Supervisor/Interviewer/Memory有真实反例和I/O次数验收，其余域通过准入清单逐项迁移。

**Non-Goals:**
- 不引入OPA/OpenFGA/Cedar，不建立通用组织/租户关系引擎，不凭空把工号、brokerId、employeeId视为Auth userId。
- 不改造全部登录token格式、refresh族或设备管理；现有access/refresh认证保留，撤销/停用事件接入版本发布。完整sid/jti生命周期另案。
- 不实现未落地签章、支付、外部发布、视频生成、共性提炼等业务；不退回匿名/无Memory路径。
- 不保证零延迟撤权、分布式副作用自动撤销或exactly-once；资源隐私删除仍沿原领域政策。
- 不修改本次规划之外的主规范或代码。实现阶段再同步LR/KI，不提前关闭问题。

## Decisions

### D1. 显式目录、业务管理员与角色组合

增加 `auth_permission(permission_code, domain, operation, active, version)`、`auth_role_permission(role_id, permission_code)`、`auth_user_role(user_id, role_id, valid_from, valid_until, active)`，沿用auth_role的active/assignable/deleted。用户增加独立 `business_admin`，不是ADMIN角色隐式通配；普通有效权限为有效角色、有效权限的并集。初始迁移把现有ADMIN映射为business_admin，现有BROKER映射为有明确权限清单的角色，不给未知角色或任意前后缀追加权限。

权限目录由版本化迁移/登记清单和代码操作契约维护；管理API可以调整角色组合和用户授予，不能创建未落地工具的任意可执行码。所有关联有审计。管理员可使用全部已登记有效操作，但实际资源范围仍由领域决定。Memory self-only不例外，服务调用ACL也不因业务管理员扩大。

首批明确权限：`agent.chat.use`、`agent.workflow.read`、`agent.workflow.artifact.read`、`agent.governance.read/manage`、`agent.audit.read/manage`、已登记的 `agent.workflow.invoke.<domain>`、MCP逐工具操作码、`interview.initiate/read/assist/end/report.generate/asset.submit/asset.publish/asset.regrade`、`memory.read/write/search/share/revoke/delete`、`memory.constraint.read/manage`、`memory.experience.governance.publish`、`auth.users.manage/roles.manage`。本人访谈读取和回答为参与关系基本能力，可由所有普通用户基础角色的 `interview.read/respond` 表达；撤销发起权限不撤销本人历史基本读取。

替代方案：每SubAgent硬编码角色或沿当前前后缀规则更省迁移，但无法精确审计操作；通用策略引擎当前成本高于业务规则规模，因此不选。

### D2. MySQL授权版本与Redis不可变快照

新增每用户 `auth_user_authorization_state(user_id, permission_revision, credential_epoch, status)`；角色权限、角色停用影响用户及管理员标记/用户授予变化在同一短事务递增受影响用户revision。目录变更同样更新受影响用户（包括business_admin）。初期用户规模按事务内批量更新实现，后期才拆全局策略epoch，避免现在引入多种版本组合。事务锁按userId排序；每次变更写 `auth_authorization_outbox(event_id, affected_users, revisions, reason, committed_at, status, attempts, lease_epoch)` 和审计。

Redis使用用户hash-tag：`authz:{userId}:head` 保存revision/credentialEpoch/status、permissionHash与独立签名的新鲜度证明；`authz:{userId}:snapshot:<revision>` 仅保存稳定去重权限、businessAdmin、catalogVersion、最早授予截止grantDeadline、contentHash与Auth签名，不含可滑动的签发时间或缓存TTL。Auth先写不可变snapshot，随后Lua条件更新head（revision只增，等版只允许相同permissionHash以及更晚的真实核验时刻），再通知。head和snapshot同用户slot；缺snapshot不能指向该revision。只有Auth写这些key和发布指定通知channel；消费者只允许精确namespace读和指定channel订阅，禁止写/TTL修改/PUBLISH/SPUBLISH及其他channel访问，使用独立Redis账号与受保护连接。

snapshot内容不因读取改变；Redis回收TTL属于缓存存储属性，过期同版可从MySQL重建完全相同内容/hash。新鲜度证明与请求context可以再次签发，但不能改变snapshot内容或延长已建立run的hard deadline。grantDeadline是最早有效授予变化点，过期授予既用MySQL定时处理发布失效事件，也靠有效期本地阻断。旧snapshot不作为新入口准入。角色批量变更、权限停用和access/logout/账号状态变更都在MySQL登记版本事件；logout第一版递增该用户credentialEpoch，使该用户所有已有运行上下文要求重新认证，同时仍仅按既有协议吊销提交的token。其他设备尚有效access/refresh可以重新认证建立新上下文：这不是“全设备退出”。同一logout请求以撤销token摘要幂等，不能反复增加epoch制造失效风暴。

选择用户级而非run级Redis集合：同用户N个run仅一份同版权限，Auth不枚举所有任务、不与业务进程共同修改权限集合；run只存版本引用/政策和执行事实。缓存缺失由Auth从MySQL重建，消费者不得回写旧内存补缓存。

### D3. 可信请求上下文与一期运行有效期

新增共享框架无关DTO：`AuthorizationSnapshot`、`AuthorizationContext`、`DelegationBinding`、`AuthorizationStatus`和固定错误码。snapshot为Auth签名不可变数据；context包含actorId、permissionRevision、credentialEpoch、permissionHash、认证expiresAt、audience、requestId/run/session绑定及policyDigest，绝不进入模型或业务metadata。权限引用本身不是Bearer；对请求持有与调用服务的证明必须另外验证。

一期默认：入口建立固定 `runHardDeadline=min(entryAuthenticatedAt+300秒, accessExpiresAt, grantDeadline)`，且快照没有授予截止时该项视为无穷。每次域context期限 `min(parentContextExpiresAt, runHardDeadline, 当前grantDeadline, verifiedAt+300秒)`；专用请求绑定证明期限 `min(now+60秒, contextExpiresAt)`。同run版本刷新或新鲜证明签发只更新版本和new-check时刻，不重置runHardDeadline；原有较早授权截止也不能因权限新增自动延长。自动刷新需仍有效的真实交互凭据或未到期父委托，不能从owner/run/旧版本号生成新凭据。停止/重启、run硬到期或原access到期后要求原用户新的认证续接，保留当前任务待续接事实，不盲重发。长访谈前端可用仍有效登录session在认证领取端刷新自己的参与票，不通过模型续票。

不改token格式：保持既有签名和parse/type/撤销检查，由新入口Auth RPC解析一次。后续内存快照不证明token永远有效；credentialEpoch通知和对账用于已接受运行的有界失效。Gateway不持用户权限写权。

### D4. 初始入口只调用一次Auth

Gateway清除客户端所有可信身份/授权头，通过 `resolveRequestAuthorization(accessToken, requestBinding)` 一次解析账号、角色和有效集合，签给确切下游受众。Supervisor本地验证Auth签名、audience、路径/方法及请求关联，再建立run；Agent filter和AgentPrincipalContext不再重复解析相同Bearer。Gateway对实际转发请求生成唯一入口requestId，计算有界原始body字节SHA-256，签名绑定actor、method、原路径、实际转发路径、规范化query、目标audience、bodyHash、授权版本与有效期；下游在JSON解析/执行前对接收原始字节和路径重新计算并比对。query按明确排序保留重复参数，禁止歧义编码/重复身份字段；路由rewrite在签名中显式保存原/目标路径，不以字符串猜测。空body使用固定empty hash；第一版不支持可变流式上传的授权路径，超出配置正文上限拒绝，不跳过绑定。MCP按实际序列化JSON-RPC method/id/params/tool arguments绑定，A2A沿实际SDK bodyHash，不只在RPC fixture核验；固定gateway marker不作认证。

直接访问业务服务的外部HTTP（无Gateway可信上下文）独立用真实凭据调用同一入口RPC；可信Gateway上下文则本地消费，不能重复算成新入口。公开路径按path+method精确列举；`POST /auth/refresh`由refresh验证自身，保留Studio现有明确豁免，不开放相似路径。请求上下文可只在内存保留到run结束，不把原token放进checkpoint。

### D5. 本地缓存、Model核版本、单飞刷新

各消费者通过common授权适配器共享有界cache：`(actorId, permissionRevision)` -> 不可变snapshot，单独保存credentialEpoch/最近权威核验时刻；每run引用snapshot与原政策，不把当前version写到模型。角色新授权可以扩大当前用户集合，但允许执行仍取原请求政策、技能/审批和域委托交集。

首次Model复用入口结果；仅业务Model重新规划读Redis head及权威新鲜度（一次MGET/pipeline，不下载snapshot）。同revision/credentialEpoch、有效期与新鲜度成立则本地继续；version变化/notification dirty时单飞调用Auth刷新签名context。每SubAgent内部model也仅在它自己的重新规划阶段核版本，不在每工具回调重查。摘要/评分/一次性造句调用不是Supervisor规划边界，不为了它们重复取操作权限。

本地状态为VALID、DIRTY、EXPIRED、UNVERIFIED。通知到达立即标DIRTY；未完成刷新前不启动受保护新动作，刷新确认仍允许的再继续。每动作仅本地检查到期/dirty/许可，不请求Auth/Redis。模型误选未获准工具返回结构化拒绝；不能已claim副作用后才发现粗权限拒绝。

### D6. 通知、对账与30秒兼容窗的真实上限

Auth outbox dispatcher独占领取epoch，在事务外读最新MySQL revision/状态并发布，失败有限重试/告警，不用缓存旧事件正文覆盖最新版本。发布成功后Redis Pub/Sub通知 `{eventId,userId,newRevision,credentialEpoch,reason}`；通知不含权限、token或资源正文。所有运行消费者订阅同一权限版本channel，按本地是否缓存该用户过滤，不需Auth按每个beacon追踪消费者。每实例去重活跃用户、10秒一次批量核对Redis head；无活跃用户不查，重新入站总走Auth。

Pub/Sub只加速，不作为唯一可靠交付。Auth每5秒对活跃缓存head进行MySQL一致快照版本/outbox核验，签署独立 `FreshnessAttestation(userId, revision, credentialEpoch, status, permissionHash, checkedAt, notAfter, nonce, kid)`；checkedAt采用读取MySQL前的时刻，notAfter不超过checkedAt+30秒，不用计算完成/晚发布时刻制造新鲜度。核验发布前再次读MySQL当前版本，变更则丢弃旧证明；Redis Lua还需head revision/epoch/hash匹配且checkedAt不倒退。核验后立即发生提交仍可能有竞争，但证明截止仍从真实核验开始，窗口不延长。消费者本地验证Auth签名和字段，再结合MySQL/outbox滞后监控；GET head不更新证明。存在未发布变更时不为旧head签新证明。许可截止取min(contextExpiresAt, runHardDeadline, grantDeadline, FreshnessAttestation.notAfter)；网络/缓存/dispatcher失效超过期限则本地EXPIRED阻止新动作。这样不会因不断GET陈旧Redis而把兼容窗无限续长。

收到撤回的消费者不用完剩余30秒；立即DIRTY并阻止相关新动作/未输出私有正文。已运行外部副作用不自动回滚，保留实际状态。账号/角色操作权限撤销可兼容；Memory来源/共享grant、业务删除/参与关系变化在原领域边界即时核验，不等待Auth outbox。

替代方案：每工具读Redis更快看到变更但网络依赖多；完全只通知会漏失效；每Model重新下载权限徒增计算。版本核对+单飞刷新+批量对账契合现有Graph/异步多轮和容许短传播窗。

### D7. 域入口独立验证与请求完整性

新增版本化原生Dubbo授权RPC `validateDelegation(parentContext, binding, callerProof)`；新A2A/MCP/业务执行由接收服务独立调用一次。binding绑定caller/audience、actor、operation集合、run/childTask、requestId、bodyHash、policyDigest、nonce和期限；调用方和接收方分别用自己的Ed25519身份签署协议标签 `bkanent-authz-v1`，Auth响应签名含原请求nonce/hash。注册公钥与caller-operation/domain ACL分开，业务管理员不扩服务ACL。Auth验证当前MySQL授权版本/状态或已权威核清缓存、新请求防重放、父context/范围，返回域子集context；子集不得超过父已授予范围。版本变化父context不可直接扩权，须Supervisor重新取当前权限并重新规划/审批。

登录/login、refresh、me及UserManagement的单role准入同步迁至businessAdmin+有效角色集合；旧roleCode仅兼容展示，不决定新授权，也不继续只准ADMIN/BROKER。RAG版本化principal/scope新增permissionRevision/credentialEpoch/businessAdmin及明确操作集合与受权业务范围，保留独立HMAC但两侧同步DTO/算法域版本。businessWideRead/activeOnly由领域根据新授权事实签发，RAG独立验证事实和空间操作交集，不把某个任意角色名字解释成管理员；legacy ADMIN/BROKER只作受控迁移映射，普通用户房源可读仍默认ON_SALE。管理员无role也可正常管理业务空间，个人Memory仍self-only。旧REST的“ADMIN唯一管理”改为受信businessAdmin管理许可，不根据普通user参数扩大。

现有A2A专用transport可承载新上下文，wire v2并行迁移；guard仍在官方TaskStore/queue前做请求和owner/caller/context检查。send/stream新执行一次验证，工具/流回调仅本地状态和范围。get/cancel/resubscribe每个新控制请求独立验证；不能因为最终工具executor没启动而跳过。Auth不再每次派发签发新USER beacon；受限delegation只能以有效父context与服务签名产生，不能从裸userId兑换。

Memory独立外部RPC是执行边界，首次验证得到短期域context；后续同context本地验Auth证明与本请求Memory服务签名/nonce，然后做当前资源check。重复内部Memory调用不重新去Auth，不能移除Memory的源/permit/fence检查。其余RAG保持现有签名协议，通过适配消费新身份，未迁移链不开放弱fallback。

### D7a. 独立的同运行授权刷新接口（用户已确认）

新增原生Dubbo `refreshAuthorization(accessToken, originalContext, requestBinding, callerProof)`，RPC操作为 `REFRESH_AUTHORIZATION`。它不同于首次 `resolveRequestAuthorization` 和跨域 `validateDelegation`：只更新尚未硬到期运行的授权版本，不创建新run、不向其他audience转移身份、不延长期限。普通重规划同版仍只核Redis head；版本变化时才由持有有效真实用户凭据的服务调用本接口。受限子Agent没有用户凭据时不得凭parent或userId刷新，需将失效交回Supervisor；Supervisor刷新后为子调用取得新的受限委托，不扩大原执行或审批。

刷新请求hash使用独立类型标签，覆盖accessToken的digest、完整原已签context（含algorithm/kid/signature）与新的RequestBinding。新binding.callerService及audience均须等于originalContext.audience，runId/sessionId/policyDigest必须等于原值；requestId和nonce为本次刷新新值，method/path/query/bodyHash覆盖实际刷新请求而非复用旧请求签名。callerProof签给auth-service、声明REFRESH_AUTHORIZATION、覆盖该hash/本次requestId/nonce；Auth核服务公钥、独立操作ACL和audience准入。不能把RESOLVE或VALIDATE证明跨类型复用。

Auth先验证原context签名及真实actor/audience/期限，不要求原permissionRevision仍为最新，也不要求旧30秒freshness仍有效——否则无法修复收到版本通知后的DIRTY状态。但原context.expiresAt与runHardDeadline必须尚未到期；解析真实accessToken且actor一致，再读取当前MySQL授权、处理授予时间边界并核最新revision/status。旧credentialEpoch必须等于当前epoch：logout/密码或账号级失效不得靠原运行刷新跨越，需新外部认证续接；停用/删除用户直接拒绝。权限revision不能倒退，当前账号与操作仍须有效。

返回新的完整稳定snapshot与freshness，签名context范围严格取 `originalContext.allowedOperations ∩ currentSnapshot.permissions`；管理员身份变化不能扩该范围。`runHardDeadline`原值保留，`expiresAt=min(originalContext.expiresAt, originalContext.runHardDeadline, currentAccessExpiresAt, currentGrantDeadline, refreshedAt+300秒)`，更早的原截止不被新增权限或更长token延长。actor/run/session/audience/policyDigest保持原值，新context的requestId/requestHash/nonce关联本次刷新；freshness从真实权威读取起算不超过30秒。

签发前复核MySQL当前revision/epoch/status，竞争变更则返回409，不签已知陈旧结果；成功准入消费caller/key/nonce持久唯一记录，重复证明返回REPLAY_DETECTED。签发期间的通知竞争仍按原30秒有界失效语义处理。消费者 `AuthorizationSession.refresh` 继续验证三签、响应关联、单调版本、原身份、原范围及不延长期限，不因新增接口放宽本地守卫。没有剩余许可可返回有效空scope，后续受保护动作明确拒绝；原审批、已完成调用和未知副作用事实不变。

验收必须执行真实provider→签名response→AuthorizationSession.refresh闭环：T0建立300秒run，T0+10改变permissionRevision后刷新成功但hardDeadline仍T0+300；原子域委托拒旧版本且接受刷新后的受限版本。增加撤权/再授予不扩scope、错actor/audience/run/session/policy、伪造原签名、错操作proof、nonce重放、epoch变化、access/context硬到期、签发并发版本变化和依赖失败反例。该接口不自动标记4.2/4.5完成，须实现与测试后更新KI-169。

### D7b. 独立执行绑定接口（用户明确选择）

新增 `bindExecution(ingressResponse, executionBinding, callerProof)`，操作为 `BIND_EXECUTION`；保留独立refresh接口，不使用刷新把null run/session改成具体身份。Gateway入口仍签实际请求字节与用户权限、不预猜持久run/session。Supervisor本地验证入口三签和实际请求，解析缺省/重投/续接/审批的真实run与session，先核持久owner、输入/政策幂等、会话归属，再调用绑定；绑定成功后才能捕获执行lease、入异步队列、调用模型或发起工具。Auth不读取业务数据库，会话归属由受信Supervisor执行点负责，绑定RPC只核授权与服务证明。

`ExecutionBinding`包含callerService、actorId、runId、sessionId、operations、原入口requestHash、归一化业务executionHash、原policyDigest、本次requestId/nonce以及短请求期限。caller必须等于原context.audience且在独立BIND_EXECUTION ACL内；只允许原context.runId/sessionId均null的入口证明，已绑定context不能再次换run/session。绑定hash以独立类型覆盖完整入口三签响应和ExecutionBinding；证明签给Auth。Auth验证三签、原入口freshness、当前revision/credentialEpoch/status、scope子集及期限，再持久消费nonce与原入口绑定记录。记录以原入口requestHash唯一，保存actor/caller/run/session/executionHash/operationsHash/policyDigest/原hard期限的最小事实；同入口不同tuple返回409，合法同tuple带新nonce可幂等重取，但不延长原截止。

签发的context保留actor、audience、policyDigest、permissionHash/revision/epoch及原runHardDeadline，加入经Supervisor核查的run/session；scope只能收窄，expiresAt不超过原context、原hard期限和当前grant截止。response.requestId/requestHash/nonce关联本次绑定，与Gateway原请求binding分开。旧入口响应/原始请求验证事实保留在请求内，不被新执行上下文覆盖；消费者建立新的已验运行session并更新原专用Entry，原权限证明不原地改签。原入口到绑定最多一次新增Auth调用；该开销不扩成每节点调用，Model同版仍只核Redis版本。

新增增量MySQL绑定表，不能修改已应用250/260迁移；绑定记录既不是用户登录凭据，也不允许从checkpoint恢复。初始REST/A2A执行接线先覆盖chat、Supervisor sync/async tasks/workflows及已有run的显式续接；审批/取消等恢复入口只在核原关联后绑定，未完成路径保持拒绝并记录未验，不虚构上线。依赖故障失败关闭，原请求冲突在任何业务副作用之前返回，权限版本竞争须刷新或重新认证后再绑定，不以旧证明放行。

验收：入口Auth一次、执行绑定一次，实际Supervisor模型执行与lease捕获使用非null绑定；同actor缺省session重投沿原持久关联，显式跨session/变输入拒绝；跨actor、已绑定换run、错scope/nonce/body/epoch/过期拒绝；同步/异步线程清理与恢复不保存凭据，已完成工具不因绑定失败重发。Gateway和下游header预算及所有新模式配置仍按启用门禁管理。

### D8. MCP第一阶段只开放可验证服务

InterviewMcpTools当前6个普通@Tool无真实provider bean，先完成明确ToolCallbackProvider/官方注册及专用HTTP鉴权过滤（或服务器支持的标准鉴权扩展）。MCP客户端在模型不可见的headers/transport上下文附binding；接收服务在新call执行入口调用Auth验证一次，同一调用内代码不逐步认证。外部MCP不支持用户委托时只允许清单标记的无私有数据能力，不把userId业务参数当身份。Supervisor动态连接管理仍需独立管理权限与地址防护。

不新增任意HTTP授权回调URL；权限RPC用受管注册中心，版本广播用Redis。涉及新增服务端URL请求时只允许http/https、请求前校验精确host并拒绝localhost、环回、私有和保留地址，同时防重定向和DNS换址；不扩大已有RAG受管网络策略，本次新增HTTP调用不借已有私网例外。内部MCP验收优先原生适配/服务器入口，不新增绕过地址策略的客户端。

### D9. Supervisor资源与执行边界修正

统一chat、sync/async、continuation/approval、SSE、audit、query的持久session owner核查。run幂等注册以actor+session+requestId定位并比较规范化输入/policy hash，重复不同正文/会话返回409。已完成/unknown工具保持原call关联；当前结果读取仍做资源/来源核验，不因先前成功绕过。管理员业务诊断全读走独立管理permission并审计，不允许ordinary audit无筛选查全局，不把诊断权扩至Memory私有正文。

将目录能力->操作许可映射从首domain/generic fallback改成精确contract；本地工具按具体操作而非统一chat.use，MCP按工具权限而非server.read代替下游资源。技能正文版本和scope继续与能力交集，AUTO新发现能力按完整权限可见。审批原call/args hash、run租约/cancel、预算/副作用账本仍实时守卫，新增transition同步KI-2回归。

### D10. 访谈主体关系与操作矩阵

在case/session增加可信created_by_user_id；新增 `interview_participant(session_id,user_id,relation,confirmed_at,revision,state)`，relation仅CREATE/RESPOND资源关系，不是Auth角色。普通用户创建需interview.initiate，受访绑定通过已认证用户接受邀请确认（邀请引用非认证票，不替代登录）。创建人录入工号只能作为业务metadata，服务端映射需可靠身份来源；没有员工目录时用平台userId明确选择+受访接受，不靠姓名猜测。第一版不支持匿名受访者。

| 动作 | 操作条件 | 资源条件 | 业务条件 |
|---|---|---|---|
| 发起 | interview.initiate | actor成为created_by | 要素校验，不代建他人owner |
| 本人列表/详情/结果/回执 | interview.read或基础参与读取 | CREATE或RESPOND；业务管理员全读 | 未删；结果确属于该session |
| 提交回答 | interview.respond | 已确认RESPOND；创建人自访时明确自受访 | IN_PROGRESS，原幂等键 |
| 题目创建/确认、启动 | initiate对应受权动作 | CREATE；管理员明确操作 | DRAFT/QUESTIONS_CONFIRMED；禁止运行后写题单 |
| 协助加问/下一题/收束 | interview.assist | CREATE；管理员明确业务管理 | IN_PROGRESS；不设导演用户 |
| 主动结束 | interview.end | CREATE；管理员明确业务管理 | 入原运行结束指令，不从工具冒用RUNTIME |
| 报告生成 | interview.report.generate | CREATE或获准管理员 | case/asset/session同源且源可用 |
| 上传/发布/重评级 | submit/publish/regrade各自许可 | 自身资料/获准管理 | 脱敏、metadata、grade及发布审查 |

权限通过不跳状态。`transition`先验组件actor和目标合法性再返回已目标状态的幂等值，不能让重复调用绕过；工具内部mode更新/重签与start分开。题目服务在任何写前核状态及关系，批次原子校验，非法questionIds不能先反确认再报错。协助指令保存actor/permission revision/来源场次并在消费前核当前关系，关闭行为保持纯运行管线。

### D11. 票据从模型结果迁至认证前端领取

A2A open/start工具只返回sessionId/caseId/entryPath/status/claimPath，不返回ticket；REST开台同样返回安全领取路径。新增 `POST /interviews/{id}/credentials`，消费Gateway可信context和参与关系，创建256-bit随机不透明票。存 `ticket_hash,user_id,session_id,purpose,expires_at,revoked_at,revision`；票有效期默认15分钟且具体请求仍要求有效登录上下文，不把业务票变成跨用户授权。创建/受访各有purpose（MANAGE/RESPOND/READ），同用户可有多条，重签/撤销只针对指定凭据，不覆盖所有人的票。

GET monitor/replies和POST turns等用专用x-ticket头+可信context核user/purpose；长期SSE用可带请求头的fetch流，不把票放query。GET stream删除query-ticket后有明确兼容期公告：旧query票拒绝，不redirect转发。撤销票在本域立即核查，Auth失效按统一版本处理；密钥更换不被当作自动票撤销。票原文领取响应标Cache-Control:no-store，并禁止应用访问日志记录授权header/body。导航引用不是票，不能仅凭引用取票。

不扩大泛化输出sanitizer放行ticket；在工具响应入模/Memory前移除真实凭据，从来源解决冲突。构造实际ReactAgent调用测试捕获工具后模型消息/归档/progress/终态/前端领取，证明无票但正式领取成功。存量归档若有票字段按受权清理清单撤销相关票并清除可恢复副本，不能读取或回显值作审计；仅记录计数/状态。

### D12. 私有和公开资产分轨，报告有来源授权

新增asset `visibility=PRIVATE|PUBLISHED`、publication revision、approved_by、脱敏投影/内容hash；默认PRIVATE。评级L2/L3是质量条件非发布许可，显式asset.publish+来源范围核查后才公开。本人参与者和管理员业务全读访问私有业务资料，raw话轮仅受权域内保留；个人Memory存副本仍owner-only，不能使用访谈管理员范围反查私有Memory。

报告提交验证case存在、asset同case、session关联与允许的源状态，记录真实source contentHash/revision/publication policy。`inputSnapshotHash`包含这些源事实和报告模板版本，不仅ID。worker只按已接受任务来源执行，模型之前再核源可用与脱敏，提交报告及最终公开读取重新核source/visibility。MySQL短事务获取lease epoch，事务外模型调用，最终CAS和报告唯一键以当前epoch/源版本提交，迟到失败也不能把新任务写成RETRYABLE。用户离线不自动取消已接受任务，但撤回/删除/来源变更阻止继续用正文及公开发布。

上传实际调用Sanitizer，不用`String sanitized=content`；缺脱敏字段不回退raw入模型，旧未知数据隔离待治理。用户明确公开只读MCP保留。仅公共投影的报告/asset列表、详情、搜索与公开结果复用要求PUBLISHED、L2/L3和有效源；本人参与者/管理员的私有业务详情与获准私有报告生成不要求PUBLISHED，但仍查各自操作、资源、关联、脱敏及源状态。公开只读无需个人委托，但仍要求清单登记的调用服务/接口和当前公开政策；上传/报告生成等写操作必须用户上下文，不因“无私有数据”免除调用服务认证。上传仍只新增，报告生成是受权后台任务，不解释成纯读取。重评级核真实metadata和证据，不固定true升公开等级。外部原文、派生结果与Memory授权共享各有政策，不自动授予跨owner Memory grant。

### D13. 操作迁移清单与停用门禁

首批仅在共享链/Supervisor/Interview/Memory正反验收后启用。其余域文件级清单记录：listing读/写状态范围；customer用户-经纪人/客户关系；business员工/门店报表；contract附件/办理/签署；settlement计算/确认/分账不等支付；compare迁移旧拒绝RPC及shareCode结果范围；media task/material owner；notification本人消息读取/发送recipient授权；marketing内容owner/编辑/发布。无法证明关系或真实能力则操作关闭，不因登记Memory caller自动开放（KI-151）。前缀所有调用与真实工具schema一并迁移，未知/未实现保持明确不可用。

### D14. 验收与兼容

测试分四级：纯权限计算/版本原子性；真实MySQL+Redis并发与故障；真实Graph/ReactAgent/MCP本地fixture；开发栈真实transport与用户反例。统计I/O次数而非猜测延迟：正常Gateway->Supervisor入口Auth一次；同版重规划只版本读取；新子Agent或MCP入口Auth一次；同执行内部N工具和M流项新增Auth/Redis每节点调用为0。Memory来源RPC是领域资源检查，单独计，不伪装成重复Auth，也不删除以凑0。

权限格式/错误HTTP状态、票领取、旧公开范围和角色映射都公告。版本v1/v2并存只用于明确已审查消费者，不允许验签失败后退v1或把缺桥认匿名。无有效新协议配置先启动失败/能力关闭；回滚成组保持新资源拒绝规则，不能回到已知审计越权或票据入模实现。Studio当前未就绪浏览器验收标未测，API/fixture不冒充UI完成。

## Risks / Trade-offs

- [撤权短传播窗] → 第一版允许最多30秒，只适用于账号/操作权限；notification dirty/硬到期立即停止新动作，资源撤回不放宽，记录change提交/发布/消费者观察时间而不记录正文。
- [MySQL与Redis非原子] → 同事务outbox、snapshot先写/head CAS、权威新鲜度不能只由GET续期；Auth首次入口从真源核版本，dispatcher滞后超过窗口不续旧head。
- [角色影响用户很多] → 当前规模用有界事务和批量锁；大规模全局epoch优化另案，不把异步用户版本更新期伪称立即一致。
- [Redis或Auth不可用] → 已知有效上下文仅剩余窗口可用，新入口/已知撤回/到期拒绝；单飞、批量对账、有界队列/缓存，不通用自动重试鉴权。
- [服务过度委托或请求篡改] → 协议域Ed25519、caller-operation ACL、父子范围交集、nonce/bodyHash绑定，消费者不能写权限Redis。
- [权限刷新改变执行计划] → 原actor、请求政策、审批call/args和账本不变；新许可只供后续模型决定，不自动执行业务或重发旧副作用。
- [存量工号未映射/已公开内容] → 数据保留、未知归属隔离、管理员审计补映射；不自动按姓名授权，不宣称撤回能收回已交付外部内容。
- [凭据生命周期影响长访谈] → 前端在仍有效登录下独立刷新参与票；run硬到期保留待续接事实，需要原用户重认证，不以延长snapshot TTL绕过。
- [其他域未落地] → 分操作准入和明确阻塞，不把统一权限建设扩成业务功能实现。

## Migration Plan

1. 盘点权限调用、三样本所有入口、当前注册服务与测试，冻结有效角色/能力映射；记录LR/KI现状，不读或输出运行秘密。
2. 只增量创建权限关联、版本/outbox、访谈主体/credential/publication/source/lease迁移；校验fresh/upgrade/repeat，保留旧用户、业务表、checkpoint与归档。
3. 发布Auth新RPC/Redis快照与ACL、dispatcher/通知/对账；影子比对旧正常操作与新显式权限，不实际扩大服务或资源访问。
4. 同组迁移Gateway/common/Agent/A2A/Memory，真实测试新入口和原任务恢复；先封审计/governance/chat旁路，禁止弱协议fallback。未迁移caller按清单停用。
5. 迁移Interviewer工号映射、参与确认、领取票、题单状态守卫、报告私有/公开投影和worker CAS；无可信归属隔离。前端契约公告和API/浏览器分别验收。
6. 按domain矩阵逐操作迁移，尤其notification/media不得使用旧“未登记caller所以安全”假设；真实能力尚未就绪则保持禁用。
7. 故障/撤权/期限/多实例/重启矩阵及全量Maven验证，严格OpenSpec校验，同步LR-33/40/41/63新语义及KI实际修复状态；每次实现验证后的中文提交推对应分支。
8. 回滚先停止新请求并排空有副作用调用、保留outbox/版本/permit和原任务事实；只能成组回退到同等资源拒绝边界的兼容制品，不删数据/卷/密钥、不恢复旧票/公开授权。基础Memory始终接入。

## Open Questions

- 生产环境准确用户/权限数量、Pub/Sub/对账批次上限和内存字节预算需测定；初版协议、30秒兼容上限、10秒对账、300秒context已确定，调参不得扩大安全窗口。
- Studio正式浏览器实例可用时间与员工目录连接器实现可后续确定；初版使用平台用户选择和受访接受，未映射工号隔离，不阻塞本方案资源关系定义。
