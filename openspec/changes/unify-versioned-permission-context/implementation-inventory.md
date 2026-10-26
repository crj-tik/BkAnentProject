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
| Listing(listing)：`/listings` CRUD/status/assets/ocr/verify、rag search/backfill/index；Tools读搜；signed READ/域RAG search/index | HTTP/A2A真实凭据；SQL brokerId/status/ocrStatus/verificationStatus+revision/outbox。读保留ADMIN未删全读/BROKER ON_SALE/SQL后复验；create仅reader、update/delete/status非write；broker→Auth未知。写/OCR/核验停用待授权；四旧无凭据RPC已拒绝不恢复。 |
| Customer：`/customers` CRUD/follow/matched-listings/entrust/remind/favorites；RPC profile/match | 请求customerId/brokerId；SQL customer/follow/favorite/entrust customer/listing/status；仅ListingReadClient HTTP Bearer签READ。客户owner未知，房源授权≠客户授权；私有/直接RPC/提醒停用。 |
| Business(实际**trade**)：`/business` KPI/workload/assessment/ranking/rebuild/turnover/store/big-dashboard；TradeTools/RPC月KPI/MCP月KPI | 模型/请求员工/门店；SQL EmployeeKpiStat/DailyWorkload/ListingTurnoverStat/StoreDashboardSnapshot。用户→员工/门店未知，read/calculate/rebuild分权，不猜domain business/全库范围。 |
| Contract(contract)：`/contracts` template/create/list/detail/status/OCR/archive/seal/risk/remind；Tools/MCP读风险；RPC风险/eligible结算 | contractId/brokerId/listing/template；SQL status/archiveStatus/sealStatus、attachment.contractId/ocrStatus。办理/附件owner未知，MockEsign/default local非真实签章；保留状态/关联，未验动作停用。 |
| Settlement(settlement)：`/settlements` rule/calculate/auto/list/detail/status/summary/batch/paid/bank-callback/commission；Tools/MCP计算查批次/RPC佣金 | employee/contract/batch；SQL record/split/rule/tier/summary/batch/payment payeeEmployee/status。合同broker拼KPI≠用户关系，员工/门店/收款未知；计算/确认/分账/支付分权，paid非实付，无回调身份/真实支付停用。 |
| Compare(compare)：`/compare` listings/reports/share/pdf；Tools/RPC/MCP比较+Tools共享读取 | listingIds/shareCode；JSON/PDF缓存无owner；loadListings仍已拒旧getListingById（KI-115），旧正向停用。share/PDF需owner/公开范围/源版本；单位/指纹/Studio独立signed比较非旧域迁移。 |
| Media(media)：`/media/tasks` submit/result；Tools/MCP同；RPC生成/提交/结果 | task/listing/content/caller；内存MediaTaskStore+MQ。受理非完成/耐久，task/material owner及精确后台许可未知，提交/读取分权；ID/caller非身份，未验生成/视频停用。 |
| Notification(notification)：`/notifications` station/email/robot/list/read/unread/workflow-events；Tools/MCP发送/listUserMessages/countUnread；RPC三发送 | 模型userId/邮箱/webhook；SQL recipientUserId/sendStatus/readStatus/EventConsume task。Tools:41-53无actor绑定，recipient未知。**nacos/memory-service.yaml:70-75、auth-service.yaml:64-67已登记caller，KI-151未登记兜底失效**；读/标读/发送停用至主体门，URL另防护。 |
| Marketing(marketing)：`/marketing/contents` create/variant/publish-status/list/search；Tools创建发布搜索/MCP发布/RPC保存绑素材状态 | HTTP/A2A经ListingReadClient signed READ；SQL listing/source/parent/auditStatus/status/externalPublishId。内容owner/编辑/审批/发布账号/素材版本未知；保留房源签名/业务状态，真实发布未证/mock不算完成，未验写停用。 |

1.1/1.2已形成源码节点、actor/操作/真源/未知映射/停用及守卫证据，不改权威LR/KI/tasks。1.3历史回归/局部assert见baseline，完整I/O实测由主会集中实施；本轮不宣称部署或勾完成。
