# 实施节点清单（1.1 / 1.2；3页）

## 页1：共享链 / Supervisor

HEAD `dbc13dbb7f18375f880d1d7fef3295357810a37c`。先读apply的proposal/design/tasks+9spec，LR-1/3/5/6/8/33/40/41/63/89/98、KI-1/2/55/59/79/80/128/151/157。只读源码/受管模板/reports，无秘密/业务DB/容器。路径相对仓库根，`...`省略src/main/java包。**部署/实际启用全UNKNOWN**；“停用”是未通过新授权准入的要求，非已关闭旧端点；目标码引用design，不虚构已登记能力。

交付前再核并行diff：Agent新增chat session claim/原session续接、Store原请求hash冲突、audit owner及business入口、governance许可审计；仅未提交源码、待主会测试，仍旧Auth调用。新common/authz/Auth授权文件不证明全部消费者已切。

| 真实节点 / actor / 现操作 | 真源、安全守卫与迁移缺口 |
|---|---|
| Auth REST login/refresh/me/logout/UserManagement；`auth-service/.../rpc/AuthPermissionRpcServiceImpl:36-75`；真实token | MySQL账号/角色；现单ADMIN/BROKER、invoke/server前缀及manage/admin/init等后缀，`auth.users.manage`。目标显式目录/多角色/businessAdmin；userId非凭据。保留token签名/期限/吊销、refresh同串护栏。 |
| Gateway `AuthTokenFilter:42-92`；Bearer→resolve→marker/userId | 保留清伪造头、精确POST refresh及401/503；余白名单现path匹配。缺audience/method/body/原与转发path/query签名，marker不能授权。 |
| Agent `security/{AgentRagCredentialFilter,AgentPrincipalContext,AgentRagCredentialLeaseStore}` | Bearer再resolve绑定DTO；currentUserId内部2次resolve。保留actor相等、300秒进程lease/容量/线程清理/冷恢复重认证；模型userId非actor。 |
| `/agent/chat`、supervisor tasks/workflows sync/async/续接/approval；Controller/Runner/Store | 现chat.use/mcp.chat.use；持久run/session/request/async/checkpoint为owner真源。保留原技能/政策/预算、call/args/batch hash、WAITING状态、lease/cancel、首终态复用、OUTCOME_UNKNOWN不重发。最新session/hash补丁未测不算闭合。 |
| query/cancel/reconcile/retry、state/artifacts、pending approvals、async status/stream | workflow.read/artifact.read；principal actor+持久owner，最新checkpoint待办判定/SSE session owner保留。KI-128状态查询500仍OPEN，完成事件不代替读取证据。 |
| `AgentPermissionService:90-175`、`SupervisorCapabilityCatalog:54-93`、Graph/worker | 现invoke.<首domain或agentId>、MCP business/compare/marketing/generic.read、local统一chat.use；目标精确能力→操作。保留Card/schema/技能版本scope/资源/审批/lease。目录每项/回调及Graph重复Auth见baseline。 |
| event-audit/diagnostics/governance | HEAD历史无scope/独立管理。最新audit ownedRunScopes+Memory session owner前后检查、business read/manage、governance read/manage及审计；仍hasPermission、版本legacy-rpc-unversioned，待测试不宣称开放安全。 |
| MCP连接/工具/debug、旧RAG REST、Studio | 现mcp tools/catalog/debug.read/admin.manage、rag.memory.search/init、rag.listing.query/index；Studio再resolve+workflow.read/users.manage。连接权≠工具资源权；专用委托/provider未知。保留地址/owner/sourceArtifact；preview/未就绪UI非验收。 |
| A2A send/stream/get/cancel/resubscribe；common-a2a transport/guard/credential/interceptor/executor | 已改为Auth签名父context+Agent逐请求Ed25519 proof；共享JDBC状态消费nonce并持久绑定taskId→actor/caller/context/run/session，预留以原nonce条件释放且等待forward+command完成。SQL迁移需应用到每个A2A provider数据库；官方SDK TaskStore/Queue仍为进程内存，重启后任务可能not-found且不从taskId恢复lease。新开关默认关闭，版本化模式仍须生产密钥/实例及跨服务验收。 |
| RAG RPC；Agent/Listing/Memory/Customer/Marketing真实消费者 | 独立HMAC principal/scope；READ/SEARCH/SUBMIT/GET_STATUS/INSPECT/PROVISION/REPLAY/MEMORY_SEARCH/AUTHORIZE_CANDIDATES。保留caller/key/space/operation、businessWide/activeOnly签名、双路AND、SQL revision/hash/删除/grant及输出复核。ADMIN未删业务全读、普通ON_SALE、个人self-only；SERVICE不获read/admin；Ed25519迁移不能删RAG HMAC。 |

<div style="page-break-after: always;"></div>

## 页2：访谈 / Memory

访谈真源case/session/question/turn/director_command/asset/report_task/report。现creatorWorkNo未绑定Auth用户，REST票仅同场持有，A2A租约用户未落工具owner。目标initiate/read/respond/assist/end/report.generate/asset.submit/publish/regrade待领域许可。

| 节点 / actor | 业务状态、来源与必须保留/补齐 |
|---|---|
| POST `/interviews` / openInterviewCase；Prep:57-111 | 工号来自请求/模型；DRAFT开台签票、REST已start，所以KI-1历史没票/缺start非当前源码。须认证CREATE、平台用户选择+受访接受；旧工号未知隔离不猜姓名。 |
| questions/confirm、generateQuestionSet/confirmQuestions；Prep:119-195 | x-ticket/模型caseId；缺CREATE及写前状态/完整题ID关联，confirm先更新后判无有效ID。非法批次原子拒绝、运行后写题停用，仅DRAFT/QUESTIONS_CONFIRMED准入。 |
| REST start / startInterviewSession；StateMachine:78-117、Tools:153-179 | GOVERNANCE→IN_PROGRESS保留；同目标先幂等后校actor，A2A重复仍改mode/重签。先验身份/职责，start/mode/重签分开；工具只导航、前端认证领取票。 |
| monitor/replies/getInterviewMonitor、turns/stream | x-ticket/ID；read需CREATE/RESPOND，回答需确认RESPOND。保留固定纯决策/脱敏/质量门/幂等/道别优先/收尾锁。replies先持久回复再session:req缓存；stream仍query票且仅heartbeat，改header fetch流+user/purpose/撤销复验；KI-12回执不全不盲重发。 |
| director-commands/sendDirectorCommand/finalizeInterview | 只查session未验IN_PROGRESS/creator；assist/end需CREATE/业务管理，FIFO/CONSUMED/收尾优先保留。finalize冒用RUNTIME直关应走原运行受权指令，Actor非登录角色，新增transition配KI-2单测。 |
| reports/tasks/detail/search；listReportTasks/searchReports/generateCaseReport/searchAssets/regradeAsset | 工号可选/ID无owner授权；hash仅CASE_CARD+caseId+assetId，缺case/asset/session/源revision许可。保留证据核验/有限重试/RUNNING过期重扫；claim epoch后成功失败仍updateById无围栏，regrade固定true非metadata证据。 |
| MCP search_transcripts/get_case_report/submit_transcript/grade_transcript/search_reports/generate_case_report | InterviewMcpTools普通@Tool+marker，未找到正式ToolCallbackProvider。仅transcript搜L2/L3，report读搜复用不核源；submit sanitized=content/caseId默认0非脱敏。公共read需PUBLISHED+L2/L3+有效源；本人私有获准读/生成不要求公开；submit/生成独立用户写，grade不授资源权。 |
| Collection/三技能 | scan仅CLOSING_LOCKED，COLLECT_PENDING失败恢复缺，缺sanitized回退raw。保留完整话轮/hash/状态机，不重跑补档。prep漏generateQuestionSet，report漏listReportTasks/searchReports；live-probe保持运行话术，不能放权限绕scope。 |

Memory真实wire目录 `common/.../memory/MemoryV2Contract:18-58`，29项均有provider委托；共同`memory.`前缀：working-set.read、receive-permit.request、messages.deliver、summary.submit、matters.query、entities.query、preferences.search、experiences.search、collaboration.search、grant.get、source.withdraw、writer.manage、boundary.check、preference.assessment.submit、preference.history.read、matter.transition、preferences.assemble、privacy-cleanup.status、experience.outcome.submit/feedback.submit/event.status/lifecycle、domain-experience.manage/search/use.check、share-grant.manage、shared-export.submit/commit/status。**operation不是Auth细许可**，现current-user=agent.chat.use+经验治理独立许可，目标read/write/search/share/revoke/delete/constraint需逐法映射。

USER域Ed25519+caller/domain/agent/context owner。版本化Memory proof v3携带Supervisor已绑定的Auth context/freshness，每个内部请求本地核验签名、actor、版本/epoch/hash、`agent.chat.use`、期限并消费唯一nonce；启用后不调Auth `hasPermission`，拒绝v2降级。兼容模式保持原入口/输出Auth查询，feature flag默认关闭。保留SQL owner/domain/agent/conversation、当前grant recipient/purpose/matter/revision/期限、source hash/revision/撤回/删除/传递依赖、先元数据后正文、Auth不占SQL锁；ADMIN不跨owner，来源下一使用/输出即拒绝不等Auth通知。A2A Memory prompt冻结已注入依赖集，模型/工具/最终输出边界核当前来源，撤权不能由刷新工作集重新授权旧prompt；writer/fence/期限/permit/sequence/event/hash唯一、原ACCEPTED结果、summary覆盖与checkpoint adoption分轨保留；cache非授权。SERVICE仅原精确permit补交付/allowlist outcome，不搜私有或申请新许可。旧REST/internal保留Bearer+X-Memory-Owner、实际session/artifact/handoff owner和响应复验；基础Memory失败零模型/业务工具。LR-89/KI-157票密钥空默认/32字节门、LR-98单Ed25519身份/协议域/独立ACL不回退。

<div style="page-break-after: always;"></div>

## 页3：九个其余域准入

现仅invoke.<实际domain>编排许可；细业务码/主体未落地不虚构。Customer未找到OfficialA2aAgent/Tools/MCP，其余8域有A2A/必需Memory；MCP声明不替provider/委托。**实际启用全UNKNOWN，未知关系/未验动作应停用。**

| 实际域及节点动作 | actor目标、数据真源、status/门禁 |
|---|---|
| Listing(listing)：`/listings` CRUD/status/assets/ocr/verify、rag search/backfill/index；Tools读搜；signed READ/域RAG search/index | SQL `brokerId/status/ocrStatus/verificationStatus+revision/outbox`；actor与BROKER来自Auth签名snapshot/context。摘要/RAG保留未删除ON_SALE跨broker范围；私有详情及create/update/delete/status/assets/OCR/verify核映射归属并检查精确读/写操作；管理员宽读仍需显式`.all`操作且不伪造broker。Customer→Listing复用原签名响应并用请求proof本地验签。REST授权filter依赖显式开关，部署公钥/路由未验，未启用。 |
| Customer：`/customers` CRUD/follow/matched-listings/entrust/remind/favorites；RPC profile/match | SQL customer/follow/favorite/entrust owner关系；actor与BROKER来自Auth签名上下文。档案及跟进/委托/收藏先核映射客户归属并检查精确操作；请求体brokerId仅作不匹配拒绝条件。Customer→Listing传原Auth签名响应和精确查询请求proof，不转发用户Bearer；Listing本地验签。REST授权filter依赖显式开关，部署公钥/路由未验，未启用。 |
| Business(实际**trade**)：`/business` KPI/workload/assessment/ranking/rebuild/turnover/store/big-dashboard；TradeTools/RPC月KPI/MCP月KPI | SQL EmployeeKpiStat/DailyWorkload/ListingTurnoverStat/StoreDashboardSnapshot；Auth尚无user→employee/store映射。八项`business.*.all`分别检查；REST受保护过滤器默认关闭，MCP映射为空，A2A仅用已验签context本地查许可。无上下文RPC在查询前拒绝，不从模型员工/门店字段推主体。源码fixture已测，密钥/路由/部署未验。 |
| Contract(contract)：`/contracts` template/create/list/detail/status/OCR/archive/seal/risk/remind；Tools/MCP读风险；RPC风险/eligible结算 | Auth签名BROKER映射与SQL `brokerId`确定合同owner，附件只在已授权父合同内读取/写入；列表在SQL按broker过滤。模板/创建/读取/状态/OCR/归档/签章/风险/提醒均有精确操作；保留状态机。Mock签章在写入前拒绝，不产生SEALED；Settlement未转发签名上下文的宽读RPC依KI-204继续关闭。入口开关默认关闭，数据库迁移未应用。 |
| Settlement(settlement)：`/settlements` rule/calculate/auto/list/detail/status/summary/batch/paid/bank-callback/commission；Tools/MCP计算查批次/RPC佣金 | SQL record/split/rule/tier/summary/batch/payment payeeEmployee/status；缺user→employee/store/payee映射、签名Contract委托与银行回调证明。规则、记录、分账、计算、汇总、批次、佣金分别使用操作码；不可信employee/contract副作用、批次、状态确认、模拟PAID及未签名callback在数据访问前拒绝。自动任务默认关闭；REST/MCP入口默认关闭/空映射，源码fixture已测，真实运行未验。 |
| Compare(compare)：`/compare` listings/reports/share/pdf；Tools/RPC/MCP比较+Tools共享读取 | `listingIds`仅作目标；Auth已验actor调用Listing请求级签名读取，不再调退役的`getListingById`。比较报告按actor隔离并绑定当前来源快照hash；私有分享/PDF要求owner与`compare.report.read.own`，显式发布要求`compare.report.share.public`，公开能力只对已发布快照生效。缺owner/source hash的旧JSON缓存不恢复；单位/指纹/Studio独立signed比较保持原边界。新权限只登记、不推断角色授权，REST filter默认关闭。 |
| Media(media)：`/media/tasks` submit/result；Tools/MCP同；RPC生成/提交/结果 | taskId仅作定位；Auth签名actor/broker、Listing行owner和`listing.assets.bind.own/all`在入队前校验。后台permit绑定精确taskId、请求hash、actor、broker、listing、Auth context/freshness、`media.generate.background`和硬截止；worker本地验签并一次领取，不逐任务回Auth。contentId因没有已签Marketing绑定而拒绝；过期队列项失败终结。MediaTaskStore仍是进程内存，跨重启恢复未实现（KI-117）；权限默认关闭。 |
| Notification(notification)：`/notifications` station/email/robot/list/read/unread/workflow-events；Agent/MCP本人及目标发送/读取工具；旧RPC三发送 | actor只取已验证Auth context；列表/未读可选`userId`只能断言actor相同，查询仍按actor；标读查询并核SQL recipient owner。`.send.own`只发本人，`.send.all`跨recipient，email/robot/workflow audit各有精确操作。MCP/A2A读取工具不暴露userId；工作流MQ事件仅接受白名单事件及Supervisor服务端注入actor/run。旧Dubbo三发送无request proof并拒绝；Contract/Customer reminder因`brokerId != Auth userId`映射未接通而缺省关闭，即使显式开启也跳过（KI-206）。`nacos/memory-service.yaml`已登记notification caller和agent域，但不授予通知数据权限且runtime keys未验；Auth配置同名caller只允许`RESOLVE_BEACON`。REST/MCP授权filter默认关闭，权限只登记、不推断角色授予。 |
| Marketing(marketing)：`/marketing/contents` create/variant/publish-status/list/search；Tools创建发布搜索/MCP发布/RPC保存绑素材状态 | 内容记录有Auth映射的`ownerBrokerId`，列表/搜索/变体/素材回写按SQL owner范围过滤；创建只能进DRAFT，当前没有独立审核通过入口。HTTP Listing读转发Auth签名响应；A2A读转发已绑定context/freshness并用HMAC绑定单个实际读取请求，Listing本地验签与`listing.read`/显式`listing.read.all`，仅返回未删除的在售摘要或显式全读范围。发布`SUCCESS`、伪造外部回执与Mock发布成功被拒；新入口默认关闭，迁移未应用。 |

### 10.6 跨域入口、资源与恢复准入矩阵（2026-10-26）

本表记录本地源码与定向回归能证明的边界。A2A/MCP接线或统一DTO只证明传输/本地校验路径，不等于生产入口、真实数据库、跨实例恢复或业务副作用已验收。各项未开放的原因和当前处置如下。

| 域 | 实际检查入口与已通过的本地证据 | 资源权威与恢复/owner事实 | 仍关闭的节点及原因 |
|---|---|---|---|
| Auth身份映射 | Auth签名`userId→brokerId`映射、审计、permissionRevision/outbox与签名内容；证据组A | Auth关系表是映射真源；授权发布依赖outbox。MySQL/Redis真实发布次序由任务11.1验收 | 授权RPC/新消费链未在生产启用；迁移未应用、Redis ACL和跨实例失效未实测 |
| Listing | REST域守卫、服务方法和Customer委托的单房源授权RPC；证据组A/C | SQL房源`brokerId/status/revision`决定owner、状态与返回范围；委托请求绑定原签名上下文和实际查询 | REST开关、公钥和路由未部署验收；未应用迁移，不能宣称线上开放 |
| Customer | REST与profile/match等服务边界从签名actor/BROKER取身份；证据组A | 客户、跟进、委托、收藏关联表决定经纪人归属；Customer→Listing为无持久任务的受限请求 | REST入口与真实服务密钥未验；Contract/Customer提醒因recipient不是Auth userId而保持关闭（KI-206） |
| Business | REST/TradeTools/RPC/MCP业务方法检查精确`business.*.all`操作；无上下文RPC在数据查询前拒绝；证据组B | KPI、工作量、成交和门店汇总表为数据源；请求员工/门店字段不构成actor或owner | Auth尚无user→employee/store映射；新REST默认关闭、MCP映射为空，真实路由/密钥未验 |
| Settlement | 规则、记录、计算、分账、汇总、批次、状态等按独立操作码守卫；拒绝副作用回归见证据组B | Settlement记录/分账/收款主体表及Contract状态是业务真源；当前不接受由模型字段恢复owner | 缺employee/store/payee映射、Contract委托与银行签名回调；计算副作用、批次、自动任务、模拟付款和callback保持关闭（KI-204） |
| Contract | 合同REST、Tools/MCP和RPC动作使用精确操作并保留状态机；Mock签章写前拒绝；证据组C/E | SQL合同`brokerId`为owner；附件须先通过父合同授权；状态仍由合同域真源约束 | 入口开关/迁移未在真实环境启用；Settlement宽读因缺委托关闭（KI-204），定时提醒因收件映射关闭（KI-206） |
| Marketing | 内容REST/Tools/MCP按owner执行；A2A单房源读携带Auth context/freshness并由Listing本地验HMAC请求；证据组C | SQL `ownerBrokerId`与内容状态决定范围；新内容仅能进入DRAFT | 发布审批入口和真实外部发布未接通；`SUCCESS`与伪造回执拒绝；A2A调用密钥/路由未实测，开关关闭 |
| Compare | REST/RPC/MCP报告、分享和PDF校验actor/owner；证据组D；`CompareReportCacheServiceImplTest`覆盖owner隔离和重启后拒绝无owner旧缓存 | 报告owner及Listing来源快照hash决定可读/分享；只有显式发布的快照可公开 | 旧无owner/source-hash结果不恢复；跨域交付journal与官方TaskStore持久性未验（KI-117），REST开关仍关闭 |
| Media | 任务permit绑定actor、Listing owner、taskId、request hash与期限；证据组D中的permit签名、单次领取及到期拒绝回归 | permit是worker执行上限；但`MediaTaskStore`为进程内存，重启后无可靠任务/结果恢复（KI-117） | 公开任务入口默认关闭；contentId缺Marketing签名绑定而拒绝；不得宣称跨重启恢复 |
| Notification | REST本人列表/未读/标读由Auth actor派生并核recipient owner；MCP/A2A不接收本人userId；证据组E | SQL recipient关系约束私有读取；工作流MQ actor须由Supervisor服务端注入 | Dubbo旧发送无proof拒绝；Contract/Customer reminders跳过（KI-206）；caller运行密钥、MQ真实鉴权/重投未验，REST/MCP默认关闭 |

**定向回归证据（各组可能共享依赖测试，不作总数相加）**：A = Auth/common/Listing/Customer 91项；B = common-a2a/Business/Settlement 11项；C = common RAG、A2A/helper、Contract/Listing/Marketing/Promotion 54项；D = Compare/Media及共享鉴权 48项；E = Notification与Contract/Customer reminders 16项。以上均是本地定向回归记录，不替代真实MySQL/Redis、部署密钥、跨实例、浏览器或外部副作用验收。

1.1/1.2已形成源码节点、actor/操作/真源/未知映射/停用及守卫证据，不改权威LR/KI/tasks。1.3历史回归/局部assert见baseline，完整I/O实测由主会集中实施；本轮不宣称部署或勾完成。
