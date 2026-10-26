# 基础批次与续批记录

## 当前实施状态（2026-10-26）

OpenSpec `spec-driven` change `unify-versioned-permission-context` 当前完成 **55/63** 项（以`tasks.md`勾选为准）。任务10.1–10.6按Auth/Listing/Customer、Business/Settlement、Contract/Marketing、Compare/Media、Notification模块完成源码与本地定向回归及跨域准入矩阵；任务3.3的专用Redis ACL/连接配置及隔离ACL正反验收已完成；任务11.2的Gateway、Agent Graph、A2A、MCP本地调用计数fixture与11.3跨模块攻击反例回归已完成。新授权入口、数据库迁移和真实跨服务密钥仍未在运行环境启用；8.3–8.5、11.1、11.4–11.7保持未完成。

Notification、Contract、Customer及依赖模块的定向回归 **16项通过，失败/错误/跳过均0**；Notification REST/MCP授权入口保持默认关闭，SQL迁移未应用，caller公钥和真实跨服务链未部署验收。Contract/Customer提醒因broker recipient绑定缺失保持关闭（KI-206）。Compare/Media与共享鉴权前批定向回归48项通过；Media任务/结果仍为进程内存（KI-117），新owner与permit协议未宣称具备跨重启恢复。域级具体边界及证据见下方10.6矩阵。

历史章节记录各前序批次当时状态；本节是当前进度权威摘要。

## Notification 模块（2026-10-26，任务10.5）

- REST列表、未读数和标读均从已验证Auth actor确定读取者；兼容`userId`只能作为可选actor一致性断言。标读确认目标记录recipient owner。
- Agent/MCP本人读取工具已移除模型`userId`参数；本人站内发送与跨用户发送使用独立`.send.own`/`.send.all`。email、robot与工作流事件审计查询各有独立精确权限。工作流事件以服务端Supervisor run metadata确定recipient，并校验白名单类型、正整数actor、session/task和可用的run/task一致性。
- 旧Dubbo三发送方法不携带可验证actor/recipient证明，统一返回`AUTHENTICATION_REQUIRED`。Contract/Customer提醒默认关闭；即便显式启用，未接通broker到Auth用户绑定前也跳过且不查询业务记录，KI-206跟踪后续解除条件。
- 新增Notification API鉴权契约、7项Auth操作码迁移与schema check；权限只登记、不自动授予普通角色。KI-151修正文档中错误的caller未登记描述，并说明源配置登记不等于运行密钥/真实调用已验。LR-150记录actor与recipient边界。
- 定向命令覆盖Notification MCP/业务服务/RPC以及Contract/Customer提醒模块，**16项通过，失败/错误/跳过均0**。没有应用SQL到真实数据库或启用REST/MCP开关；生产密钥、MQ认证和真实多服务调用仍待主验收。

## 跨域入口、资源与恢复矩阵（2026-10-26，任务10.6）

- 已按Auth、Listing、Customer、Business、Settlement、Contract、Marketing、Compare、Media、Notification逐域记录可信入口、资源真源、可证明的owner/恢复事实、本地回归组和仍关闭的节点，详见`implementation-inventory.md`的10.6节。
- 回归证据按模块批次引用，不将有共享依赖的测试组相加；本地fixture/A2A wiring不被表述为生产准入或全域恢复通过。
- 仍未完成：Interview浏览器领取、真实ReactAgent归档链及历史凭据扫描（8.3–8.5）；真实MySQL/Redis发布与撤权失效（11.1）、开发栈端到端（11.4）、全量Maven回归（11.5）、迁移/回滚（11.6）及最终同步归档（11.7）。任务11.2调用计数fixture与11.3本地攻击反例矩阵已完成；并不代表真实部署联调。Settlement主体映射、KI-117跨域恢复、KI-206提醒收件绑定继续关闭。

## Auth/Redis权限ACL与专用连接（2026-10-26，任务3.3）

- Auth使用独立`authz-writer`连接，Agent使用独立`agent-authz-reader`连接；两侧启用前均要求明确配置确认、专用用户名与密码，分布式模式强制TLS，连接bean不参与覆盖共享Redis的默认候选。
- `configure-authorization-redis-acl.sh`与`verify-authorization-redis-acl.sh`按精确命令、key和channel配置权限。既有隔离Redis验收记录包含23项ACL正反检查：Auth仅写授权命名空间并发布指定channel；Agent只读head并订阅指定channel，不能写权限/TTL、发布或访问其他key/channel；实际订阅收到Auth无敏感探针。
- Auth/Agent配置测试覆盖缺配置、错误ACL用户、分布式无TLS拒绝及安全开关默认关闭。证据见`acceptance-results.json`和两侧`*AuthorizationRedis*Test`；本次只核对已有记录，没有连接或变更当前运行Redis。
- 该项证明隔离ACL策略和本地连接准入，不证明服务使用生产凭据完成TLS连通或Auth outbox→head→通知全链。应用运行、真实MySQL/Redis并发和撤权窗口仍由11.1/11.4验收；新授权开关保持关闭。

## 状态

本批以基线dbc13db实施，新增权限MySQL真源、签名DTO/请求关联、角色管理、授予时间边界、本地不可变缓存和独立运行状态。新Auth RPC/管理服务在auth.authorization.enabled=false（缺省）时不装配；Redis writer未自动装配、publisher/consumer/版本刷新尚未接线。不是新授权链上线，不允许据已有DTO去掉旧消费端安全守卫。

## 已完成任务与证据

- 1.1/1.2：implementation-inventory.md含共享链、三样本和九个其余域逐节点actor/操作/资源/状态/来源/未知启用清单，已读相关LR/KI。
- 1.3：baseline.md采样点和原Memory Auth计数assert；本轮实际定向日志按模块摘要合计413项（common40、common-memory2、common-a2a23、Auth59、Agent111、Memory96、Interview82），失败/错误/跳过均0。Auth新RPC测试断言入口tokens.parse一次且snapshot一次；Memory仍保留原当前source/输出/Auth计数。没有宣称目标热路径新增Auth/Redis=0，后者是11.2未来验收。
- 2.1：真实隔离MySQL8.4.11五次fresh/upgrade/repeat及结构检查、20谓词通过；unknown用户/角色保留，repeat不复活撤权/permission/role/grant。manifest250/check及runner仅增加upgrade:bk_auth，fresh/upgrade checksum dry-run均0。
- 2.2/2.3：多角色并集、登记权限和管理员无角色、未知码拒绝；真实token/current权限的角色CRUD/用户授予/admin独立管理，写版本/outbox/审计同事务；初版基础相关35项H2/纯/HTTP测试通过。
- 4.1：30项common授权基础/本地缓存/session回归通过，canonical类型分域Ed25519、origin/receiver独立证明、严格父scope/deadline与DTO序列化；另2项AuthzException固定安全Throwable映射回归。

## 其他已写但未整项完成

- 2.4仅事务Repository原语、管理路径已接版本；旧账号/UserManagement全路径未接。
- 2.5授予开始/到期版本+outbox扫描已实现；logout epoch接线未完成。
- 3.1/3.2/3.4的签名snapshot、CAS、outbox与Freshness实现已完成；3.3专用ACL、隔离Redis正反检查及安全连接配置门禁已完成。真实服务TLS连接、MySQL/Redis并发发布与应用级失效窗口属于后续11.x环境验收，不从ACL脚本结果推定。
- 4.2仅两RPC初次认证和委托签发/provider测试5项；没有保持原run限期/范围的版本刷新协议，见下。
- 4.5/5.1仅common硬期限/session/cache基础未接Supervisor。
- 5.5/5.6/5.7已有chat/session/requestId/audit/governance边界补丁及63项先期回归；完整集又补真实request-scope四入口16次重投/冲突测试，整体部署与新版本许可consumer仍未完成，不勾。

## 失败与修正记录

- 管理Service初轮H2 generatedKey包含timestamp多列，改指定id；Controller PathVariable缺显式名已补，不放宽权限。
- 首批Supervisor测试audit可选参数未显式名，补生产name；H2审计fixture按原handler填时间。
- 集中回归第一轮新RPC子binding用fixture建立时刻，晚于入口响应跨秒时反而早于parent签发，正确被拒；改使用已签parent时间，不放宽期限。
- 集中回归第二轮授予开始Instant纳秒经SQL timestamp舍入，固定测试秒级时刻，生产边界条件不改；第三轮413全过。
- review发现新增前置幂等在principal缺省session每次随机UUID时误拒合法重投，已补原owner持久session恢复及真实HTTP反例。
- 原生MemoryArchiveProviderTest输出Dubbo injvm deep-copy默认fastjson2 JSONB诊断但测试通过，现用构造器序列化契约与资源验证不放宽，不称真实部署无日志故障。
- 隔离MySQL初次health因Windows只读bind的option文件被mysql视为world-writable而忽略，只修新容器复制后chmod600；最终成功，不改dev凭据。
- manifest首dry-run65因closed target不含upgrade:bk_auth，只加这一合法目标后fresh/upgrade为0，保留历史失败。

## 基础批次历史暂停原因（KI-169，续批已修）

现两个RPC仅resolveRequestAuthorization(token,binding,proof)与validateDelegation(parent,binding,originProof,receiverProof)。入口每次新建now+300 hard deadline；委托要求parent revision/epoch当前有效。权限变化后旧parent不能委托，重新resolve又增加原run截止/受限child范围，被AuthorizationSession.refresh正确拒绝。consumer不能裁剪Auth签名自救。

需要修订design D3/D7、共享RPC和4.2/4.5任务：建议新增明确refreshAuthorization(token,originalContext,binding,callerProof)，认证原actor并验证原context签名、policy/身份关联，在原hard deadline内签新revision及旧scope与当前权限交集；不能把refresh变成裸userId续租或延长期限。不在本批擅自新增协议；用户确认修订后才继续。

## 安全与运行边界

acceptance-results.json记录MySQL20、RedisACL23、生产Lua20及临时资源清理；并非新Auth/RPC/签名/广播全链实测。只使用新tmpfs、唯一internal网、无端口，两个临时容器/网均清理、删除卷数0。没有devDB迁移/生产部署/真实数据与外部业务副作用，没有读取.env/.secrets，不回显凭据。

基础批次原工件63项仅7项完成；续批状态见下，不将历史记录当当前进度。LR-103/104与KI-169/168同步说明实际实现和未验边界；其余不提前关闭。基础提交401c583已创建；首次push因远程独立业务规划先推送而拒绝，合并保留双方工件，将远程已发布LR-99至102与KI-167保持不变，本地新条目使用LR-103/104与KI-169。这是编号整合，不修改另四份业务提案范围；整合提交fa763e4已推送，不归档变更。

## 独立刷新续批（用户已确认）

用户确认独立接口后，design D7a、proposal、版本授权spec及任务4.2同步修订。实现 `refreshAuthorization` 和独立REFRESH_AUTHORIZATION签名类型，验证真实access、原context、调用服务、当前credentialEpoch、请求关联与持久nonce；允许旧权限revision刷新但固定actor/run/session/audience/policy及原期限。返回原scope与当前权限交集，原本地Session可接受；已接受窄scope后回放旧宽context取得签名结果也不能恢复运行许可。KI-169协议问题已修，实际消费链准入仍待KI-170。

本批新增完成2.4、2.5、3.1、3.2、3.4、4.2、4.5，共14/63。任务3.3/3.5/3.6以及4.3/4.4/4.6/5.x和领域接入保留未完成：有安全配置/通知/监控基础不等于生产ACL、完整角色响应、实际Graph与业务域执行链已启用。

账号创建、状态/角色修改、密码重置及删除现在通过同事务版本/outbox/审计；注销先持久摘要PENDING阻止所有token解析重入，再在事务外Redis吊销，失败返回待重试，同摘要不重复增加epoch。旧token格式保留，payload和signature要求canonical unpadded Base64URL，拒绝等签名字节别名绕撤销。所有新版账号写在密码计算后writer锁内复验实际access、actor及当前管理资格，旧actor-only入口拒绝，legacy模式兼容。

Auth新增有界outbox dispatcher、单调CAS发布、FAILED事件阻断旧head续鲜、真实读取起点30秒证明、共享有界active ZSET及5秒权威核验，成功认证/刷新登记活跃actor；Redis网络不占SQL事务或active map锁。共同VersionMonitor和Agent只读Redis监控基础处理通知与10秒对账，头必须完整验签，通知不授予权限；默认不启用。

Gateway/Agent原型已通过实际原始body/path/query绑定、三签、单次Auth和本地actor消费测试。当前Gateway仍签null run/session，无法等同Supervisor的持久执行身份；Agent在启动/capture处409失败关闭，不consumer填入或重签原证明。route rewrite、header预算、直接服务独立认证和实际Model/子域刷新接线未验；这是KI-170执行关联契约阻塞，不能用原型开启生产链。需精确确定新建/省略session重投/continueRunId/审批恢复的非授权关联标签怎样绑定，并保留持久owner/幂等资源核查。

## 续批验证与已修失败

- 最终定向：common55、Gateway17、Auth121、Agent83，共276，失败/错误/跳过均0。
- 全量仓库settings compile：23模块成功；全量test：23模块成功、1519项、失败/错误0、14项既有外部依赖门控跳过。通过日志的WARNING级含跳过摘要与实际Surefire XML总数一致，不把只统计INFO的929误报总量。
- 独立代码复核发现并修三个P1：token编码别名绕注销；旧context恢复已收缩scope；账号writer内只核actor失去真实凭据竞争。新增canonical别名、旧宽context回放、真实BCrypt期间注销/到期与零写副作用反例。
- 编译/fixture失败修正：common WireCodec将既有test-scope Jackson依赖提升compile；Gateway重复Mockito设桩改doAnswer/doThrow；账号typed varargs fixture修正；Publisher UUID nonce改32字节URL编码，合法空审计事件直接CAS完成，网络锁范围缩短；Agent参数重名与默认关闭minimal context配置注册修正。未放宽生产权限。
- `refresh-acceptance-results.json`：隔离MySQL22、active Lua24、publish16、ACL22，共84检查通过；新增260迁移/check、fresh/upgrade dry-run均0。仅新tmpfs/internal网且无端口，两容器/网/临时秘密已清理，未devDB apply；没有Java跨两存储注销/TLS/部署全链证据。
- 新授权开关仍默认关闭，没有生产部署、真实业务副作用或对Memory私有范围放宽。LR-106和KI-169/170/171记录真实边界；独立刷新修复不宣称全链完成。

## 多角色响应兼容续批

本轮在不改变token payload、执行关联或任何授权签名的前提下完成4.6剩余响应信息：AuthTokenDTO追加businessAdmin/roles/permissionRevision/credentialEpoch并保留旧7参数构造，UserItem保留旧8参数构造，CurrentUser旧6参数构造不变。legacy构造默认false/空集合/0，不由roleCode推新授权；tokenDTO日志不回显凭据。

login/refresh/me和管理列表/写入结果从同一次AuthorizationFacts读取提取有效角色及权限版本/epoch，排除停用/删除/未来/过期授予；角色字段仅展示，真实admin来自SQL。新版用户筛选以有效关联EXISTS在分页前限制，count/page共用相同now与谓词，次角色能命中且多角色不重复行；无角色管理员仍可列管，停用账号可管理但不能登录。旧模式仍走原mapper行为，不强制新schema上线。

AuthorizationUserInfoTest新增5项真实H2/MyBatis谓词与DTO/JSON兼容回归；与现Auth测试共35项通过。全量settings test 23模块成功、1524项、失败/错误0、跳过14项既有外部依赖门控；该总数按包含WARNING摘要和Surefire XML核对，不与先前1519重复相加。只读复核未确认严重问题。

多角色响应兼容交付时为15/63。此后任务4.4与Redis通知/对账基础完成；Gateway实际转发验收（4.3）、Graph/Model与新领域consumer（5.x/6.x）及运行配置仍待完成。

## Supervisor独立执行绑定续批（2026-10-26）

按D7b新增`BIND_EXECUTION`：Auth只接受完整签名且尚未绑定的入口context，以原入口requestHash为唯一键保存不可变actor/caller/run/session/policy/scope/executionHash和原deadline；同tuple新nonce幂等重取，重放旧nonce或改变tuple冲突。Agent协调器在Supervisor提交边界先核查已有run owner、session和输入幂等，再绑定实际run/session；绑定后才登记执行与捕获短期credential lease。执行输入hash复用`OrchestrationStore`的规范化算法，refresh不改写入口证明。直接Agent HTTP在两项可信入口头都缺失时，用实际token及method/path/query/body发起一次独立resolve；缺一项、篡改证明或actor替换失败，不读取固定marker。

执行期Graph节点和子Agent工具回调不调用协调器；一次入口绑定只发生在Supervisor执行入口。lease里只在进程内暂存已绑定context，不把Auth response、proof或token写入request/checkpoint。异步任务进程重启后的原owner重新认证/恢复仍未实现，故后续运行恢复相关任务保持待办，新授权开关继续关闭。

定向验证：common签名和hard-deadline 4项、Auth provider 10项、Auth MySQL binding repository 29项、Agent ingress filter 4项、Supervisor/direct HTTP MVC 7项，均通过；合计54项，失败/错误/跳过0。验证仅覆盖代码fixture和H2，不代表Gateway路由/头预算、Redis/TLS、真实异步跨实例恢复或部署验收。

## Gateway与Agent授权入口续批（2026-10-26）

任务4.3源码复核确认Gateway在入口过滤时对原始body做有界读取、计算hash并原字节回放；签名绑定method、原始path/query、audience、requestId和nonce。Agent filter在JSON解析前复核Auth签名、实际method/path/query/body；不匹配在进入业务链前拒绝。带rewrite的route、歧义身份query、超限及不支持的SSE/multipart/NDJSON正文失败关闭，伪造的授权与principal头在公开/受保护请求中均清除；公开path按精确method/path匹配，`POST /auth/refresh`保留专门回归。

新增Agent实际JSON伪造身份反例：请求正文中的actor/session/admin/permissions不能替代签名actor；Auth签名后修改actor字段，filter以409在业务解析与副作用前拒绝。Gateway filter/wire codec 15项、Agent入口filter 5项通过，`gateway,agent-service -am`定向构建成功。本批OpenSpec任务4.3完成，当前19/63。

运行边界：路由受保护audience与生产头预算尚未在启用环境验证；新授权开关保持关闭。Redis ACL脚本与连接身份配置已提交，但真实Redis ACL/订阅验证没有运行环境证据，任务3.3仍未完成。Supervisor异步重启恢复、业务Graph/Model及A2A/Memory领域consumer仍待5.x/6.x。

## A2A请求鉴权与持久归属续批（2026-10-26）

按模块推进6.1与6.2：Agent对每个真实JSON-RPC body签逐请求proof，接收端本地核父Auth context/freshness、调用方proof、actor/run/session、task、操作与body，不为每次subAgent调用联系Auth；共享JDBC store跨实例消费nonce、持久绑定taskId到actor/caller/A2A context/run/session，并用task+request nonce维护在途互斥。forward与SDK执行命令均结束后才条件释放；数据库未配置或迁移schema缺失时版本化模式不能启动。Auth调用仍只在Supervisor规划刷新边界。

新增MySQL迁移`20261026_a2a_task_authorization.sql`，需分别在提供A2A服务的业务数据库应用。执行lease仍是本地scope；SDK默认TaskStore/QueueManager仍在内存，进程重启后状态查询/重连可能返回not-found，不从taskId恢复身份或重建lease。版本化新链仍默认关闭，尚无真实MySQL/多实例与生产密钥联调。

验证：common-a2a全量127项，失败/错误0、跳过1；Agent全量265项，失败/错误0、跳过6。新增H2跨实例nonce/owner/并发reservation/条件释放回归。严格OpenSpec校验通过。已完成计数28/63；6.3–6.7的Memory、MCP、RAG与网络边界仍未完成。

## Memory来源授权与在途撤权续批（2026-10-26）

按模块完成任务6.3–6.4。Memory v3请求proof携带Supervisor已绑定的Auth context/freshness，并把context、freshness、实际请求body、actor/owner、operation和nonce一并纳入服务身份签名。Memory在入口和最终结果复验时本地验Auth签名/ACTIVE freshness、actor、permission revision、credential epoch、hash、`agent.chat.use`及run硬截止；授权上下文模式启用后内部Memory请求不调用Auth `hasPermission`，而owner/domain/agent、grant、来源版本与撤回、writer fence、permit及最终结果继续由Memory真源核验。旧v2 Auth调用仅保留在默认关闭的兼容模式，开启版本化模式后拒绝降级。

A2A Memory加载prompt时固定该轮来源依赖；模型/流、工具和最终输出边界都重新检查这组来源，撤回或无法核验时清除工作集缓存并中止，不把刷新后的快照当成旧prompt授权。新增KI-173记录并关闭“活动Graph轮次沿用已撤回来源”的源码缺口，LR-142记录两类权限边界分离原因。

完整回归覆盖`common`、`common-memory`、`common-a2a`、`memory-service`、`agent-service`及其上游模块：8个有测试的Maven模块共**853项通过，失败/错误0，14项既有外部依赖测试跳过**；9个reactor模块构建成功。Memory v3配置仍默认关闭，Auth公钥、真实多实例/数据库与部署启用不在本次源码验收内。严格OpenSpec校验通过后，当前完成进度为**30/63**；下一模块从任务6.5继续。

## Agent/Supervisor本地授权与异步边界续批（2026-10-26）

按模块完成任务5.1–5.7。`AgentAuthorizationConsumer`在业务Model重规划边界单次核对权限head；同版沿用本run/session上下文，版本变化时只对该run/session刷新签名context并收缩原scope。A2A/MCP/本地工具按稳定`CapabilityId`精确映射操作许可，目录和callback均本地校验；图节点不逐次调用Auth，资源、Memory来源、审批hash、租约/取消及预算守卫保留。治理审计从签名context记录actor及权限版本。

异步SSE状态查询在切换工作线程前捕获当前请求短期授权/RAG上下文，查询结束清理，不写入任务记录、事件或checkpoint。持久结果畸形/错配时保留状态但抑制结果；当前Memory来源拒绝时也不回退到存储结果。owner边界、请求幂等和审批恢复仍在原持久化入口检查。

Agent定向回归13个测试类、86项通过，失败/错误/跳过均0；8个Maven reactor模块成功。KI-128仅完成源码保护和回归，因缺少原环境重放证据继续OPEN。异步跨实例/重启恢复、A2A/Memory和领域consumer由6.x继续验收；新授权开关保持默认关闭，未作生产部署。当前进度26/63。

## MCP请求级鉴权与已验签actor续批（2026-10-26）

按模块完成任务6.5。`common`提供MCP JSON-RPC逐请求proof、严格正文绑定、Auth context/freshness与Agent服务签名的本地验签、受控server filter及有界单实例nonce guard。Agent MCP transport通过SDK隐藏transport context传proof，只接受精确登记的静态第一方endpoint+audience；Supervisor只用当前绑定run actor和精确工具操作，Graph内MCP工具调用不逐次访问Auth/Redis。第三方工具只能以无用户身份的显式public capability接入。

Interview正式`ToolCallbackProvider`仅在服务端本地验签guard启用时注册。Notification MCP本人消息列表和未读数从已验证actor取userId，模型提交其他userId时在服务调用前拒绝；发送通知的recipient userId继续作为目标对象，不混作当前actor。管理员连接管理仍与每个MCP工具操作分离；映射/密钥默认未启用，因此不据源码验收宣称任何MCP生产工具已开放。

新增contract/filter、Supervisor actor mismatch、provider条件和Notification owner查询回归；16项定向用例通过。全量 `mvn test` 覆盖23模块，1615项运行、失败/错误0、14项既有外部依赖测试跳过；OpenSpec严格校验通过。LR-143记录本地签名边界；KI-174保留OPEN：进程内nonce guard无法阻止另一副本或重启后短期内重放同一proof造成重复副作用。

## RAG签名权限上下文与出站边界续批（2026-10-26）

按模块完成任务6.6–6.7。RAG v2 scope继续使用独立owner-domain HMAC，同时只能携带一种由Auth签名的授权证据：直接领域入口解析得到的`AuthRagPrincipal`，或Supervisor当前已绑定run/session的`AuthzResponse`（snapshot/context/freshness）。Agent按每次实际检索请求签名scope与正文hash，并在RAG版本化模式不转发用户bearer；RAG本地校验两层签名、actor、permission revision/credential epoch、ACTIVE/freshness/硬截止、精确操作和空间范围，不逐次请求Auth。房源的`businessWideRead/activeOnly`由可信businessAdmin与权限证据决定；普通用户房源检索限定ON_SALE；个人Memory始终owner=self，即使businessAdmin也不扩权。未知空间、未登记操作、旧弱scope或缺少版本化证据在新模式拒绝。所有版本化消费开关保持默认关闭。

RAG出站URL复核确认本批未新增任意HTTP URL权限回调或新的服务端URL目标；权限/身份RPC仍用Dubbo。现有RAG受保护HTTP transport继续在连接前按精确host和DNS解析地址校验、拒绝localhost/环回/私有/保留地址、禁用redirect并绑定校验后的目标地址。

验证：common、Agent、Memory、RAG与Listing跨模块定向回归共**69项通过**，失败/错误/跳过均0；RAG HTTP出站与DNS/host策略回归共**175项通过**，失败/错误/跳过均0。测试证明本地序列化、验签、消费端资源策略和受保护transport回归；未验证真实部署的Auth公钥登记、密钥轮换、多实例调用或生产启用。严格OpenSpec校验通过后，当前进度为**33/63**。LR-144记录RAG的双签名和请求级消费边界；KI-57仍OPEN，完整ES合规目标的部署联调不由本批代码测试代替。

## Interview鉴权与凭据边界续批（2026-10-26）

按模块完成任务7.1–7.5、8.1–8.2及9.1–9.6。Interview参与者资格与临时票由持久化owner、session、purpose、hash、过期时间及撤销状态约束；受保护请求从已验证actor派生身份，题单、流、MCP与报告仍执行本域关系/来源/状态检查。上传原文经凭据形状清理后仅保存在受权域内，历史轮次、追问摘要和归集副本再次清理，报告默认私有且公共读取需满足发布条件。

采用请求级签名授权上下文：Gateway/Supervisor入口建立绑定身份，执行请求带逐请求证明，由接收端本地验证；Graph内部多次调用subAgent不逐次远程调用Auth，权限变化仅在规划/刷新边界联系Auth。模型生成的userId、role或admin字段不能替代已绑定actor。该边界已记录在LR-1/LR-145及A2A任务6.1中。

凭据领取契约要求认证POST、no-store与专用请求头；SSE由fetch流携带headers，移除query票据入口。浏览器前端不在此仓库，故浏览器领取/流接线、真实ReactAgent归档/artifact链及存量凭据扫描分别保留任务8.3–8.5未完成；KI-202/KI-203记录历史数据扫描与前端验收边界。四项增量迁移及schema checks已登记manifest并完成静态路径/哈希校验，尚未应用到MySQL。

验证：`mvn -gs .mvn-settings.xml -s .mvn-settings.xml -pl interview-service -am test`的7模块reactor成功；Interview 120项通过、失败/错误/跳过均0。可读Surefire报告合计487项，其中1项既有跳过、失败/错误0。OpenSpec严格校验通过。任务进度由33/63更新为46/63；未运行全仓测试、浏览器端到端验收或存量数据库扫描，新授权模式没有生产启用。

## Business/Settlement 操作授权续批（2026-10-26）

按模块完成任务10.2的源码准入与失败关闭。Auth目录新增21个Business/Settlement精确操作码，不向普通角色默认授予；业务管理员只能通过签名授权上下文取得Auth已登记操作。Business报表、评估、排行榜/重建、门店与大盘查询分别在共享服务方法检查操作，因此REST、RPC、A2A与MCP走同一守卫。REST入口过滤器采用默认关闭配置，A2A复用已验签run context，MCP依赖精确工具操作映射且当前仍为空；无上下文RPC在查询前拒绝，不退化成SERVICE全权。

Settlement的规则读取/写入、记录读取/创建、计算、分账读取/计算、汇总读取/重建、自动生成、状态变更、批次和佣金读取拆分操作。当前没有可信userId→employee/store/payee关系或Contract请求委托，所以计算、自动生成、批次、状态确认均在触库或跨服务RPC前拒绝；旧模拟付款和无签名银行callback保持硬拒绝。自动任务默认关闭，Nacos默认值同步设为关闭。Auth迁移`20261026_business_settlement_authorization.sql`与schema check已加入init manifest，尚未应用到数据库。

新增跨协议本地操作检查及拒绝副作用回归共**11项通过**：common-a2a 2、Business 3、Settlement 6，失败/错误/跳过均0；`common-a2a,business-service,settlement-service -am`定向测试构建成功。严格OpenSpec校验待本批结束前复核。10.1映射实现已由Auth、Listing与Customer模块回归验证；新鉴权入口仍未部署启用，不据源码测试声明生产开放。

## Auth经纪人映射与Listing/Customer准入验证（2026-10-26）

按模块复核任务10.1。Auth已有显式且唯一的`userId→brokerId`映射表及受审计管理API；映射改变在事务内递增用户`permissionRevision`、写授权outbox，并进入签名snapshot/context及规范hash。Auth不自动猜测旧工号或同号ID，未回填映射的用户继续无法执行经纪人私有操作。Listing/Customer从验签上下文读取BROKER，不采信模型或请求体替代；房源摘要/RAG仍只暴露未删除ON_SALE跨broker范围，私有详情/写操作核归属并使用独立写权限，Customer档案及跟进/委托/收藏按可信broker关系校验。Customer委托Listing时传原签名响应与请求证明，Listing本地验签并检查精确读取权限。

Auth、common授权DTO/验签、Listing和Customer定向回归共**91项通过**：common 21、Auth 45、Listing 17、Customer 8，失败/错误/跳过均0；9个Maven reactor模块成功。验证涵盖映射唯一性、审计与版本outbox、签名字段及hash、actor/请求体伪造、缺少权限、跨broker私有资源拒绝和Customer→Listing本地验签RPC。映射迁移和schema check已登记但未应用到MySQL；Listing/Customer生产公钥、路由和新入口未验证/未启用。任务10.1及10.2完成后为**48/63**；其余任务仍按真实数据库、浏览器和多实例运行证据分开验收。

## Contract/Marketing授权边界续批（2026-10-26）

按模块完成任务10.3。Contract使用签名BROKER映射确定合同owner，列表在SQL按broker过滤；附件通过已授权合同父记录读取，状态、OCR、归档、签章及风险动作检查精确操作。Mock电子签章不能写入`SEALED`。Marketing内容新增ownerBrokerId并在列表/搜索/变体/素材回写时按SQL owner限制；新内容固定DRAFT，当前没有独立审核通过入口；真实发布未接通时拒绝`SUCCESS`并拒绝伪造外部回执。原业务状态机继续作为单独约束。

修复KI-205：版本化A2A lease没有legacy RagCredentialContext，Marketing现在将Auth签名context/freshness随单房源摘要RPC传给Listing，并以服务HMAC绑定真实请求。Listing本地验证caller proof、Auth context/freshness、actor、revision/epoch/hash、run/session和截止，执行`listing.read`或显式`listing.read.all`并按SQL状态过滤；不转Bearer、不伪造snapshot、不逐次联系Auth。该RPC受Listing授权开关控制，默认关闭。

Auth权限目录/营销owner增量迁移与schema check已登记init manifest，尚未应用到MySQL。定向回归覆盖common RAG canonical proof、A2A/业务授权helper、Contract、Listing、Marketing与Promotion：**54项通过，失败/错误/跳过均0**；相关10个Maven reactor模块成功，OpenSpec严格校验通过。Listing/Marketing/Auth运行密钥和真实跨服务调用仍未验收，新链未启用。任务10.3完成后进度为**49/63**；3.3 Redis ACL运行验收、8.3–8.5浏览器/存量数据，以及10.4起的其他域与11.x故障交付验收继续保持未完成。

## Graph/A2A/MCP调用计数本地验收（2026-10-26，任务11.2）

按本地正式入口、Graph和传输customizer fixture完成任务11.2。Gateway接受的转发请求由`GatewayAuthorizationFilterTest`确认只调用一次`resolveRequestAuthorization`；四个Supervisor执行REST入口分别只调用一次`bindExecution`。直接访问Agent且未携带Gateway入口证明时，独立调用一次Auth resolve并在执行关联确定后调用一次bind；这是两个不同职责的边界调用。有效入口证明路径不重复resolve。

新增Graph计数用例：同一Model规划批次实际执行3个工具，产生3条`tool.completed`与1条`supervisor.completed`流事件；同版本重规划只读取一次Auth head，Auth刷新为0次，3个工具和4条流事件都没有增加Auth/Redis调用。Memory存储输出授权单独计数为4次，与head读取计数分离。相同版本不刷新Auth的消费者闭环由`AgentAuthorizationConsumerRefreshTest`复核。

MCP transport customizer fixture发出5个不同JSON-RPC `tools/call` body，每个proof均在本地按实际body验证且nonce唯一；初次规划head读取为1次，5次工具请求没有增加读取或Auth RPC。正式A2A Java client fixture覆盖send、stream、get、cancel、resubscribe五类POST，本地签署每个请求proof，版本化路径Auth beacon RPC为0次。Gateway、Agent与A2A入口/传输以及Graph/MCP计数由定向回归共**60项**验证：Agent 46、Gateway 11、common-a2a 3，失败/错误/跳过均0。

这些是隔离的本地fixture计数，不代表真实Redis、MySQL、运行密钥、跨服务部署或生产Graph/ReactAgent流量已经联调；真实存储撤权与故障矩阵继续由11.1/11.4验收。任务11.2完成后进度为**54/63**。

## 跨模块授权攻击反例回归（2026-10-26，任务11.3）

补齐A2A verifier的直接反例：合法签名但错误受众、签名正文中的task被替换时，官方handler均不执行；已有用例覆盖同body重放nonce与篡改正文拒绝。Agent入口测试用LLM JSON伪造actor、session、`businessAdmin`和`permissions=["*"]`，仍只接受签名actor；伪造marker、重复头、改body均拒绝。

跨用户/多会话与范围收缩由A2A持久task owner/JDBC共享nonce测试、`AuthorizationSessionTest`的共享snapshot但隔离run/session/policy测试、Auth绑定的actor/caller/policy/扩权拒绝，以及Supervisor审批恢复原始参数/owner/policy冲突测试覆盖。权限刷新只能与原context交集，不能扩大首次请求或审批范围。

管理员`interview.read.all`可读取业务访谈但不授予`memory.read`，Memory RAG仍把管理员个人scope限制为自身owner且不启用`businessWideRead`。Gateway、Auth、Agent、A2A、MCP、Interview、Memory和common的选定回归共**171项通过，失败/错误/跳过均0**（按模块：common 39、common-a2a 16、Gateway 15、Auth 33、Agent 51、Memory 11、Interview 6）。

上述均为本地单测/fixture；没有推断真实MySQL/Redis并发、部署密钥、跨服务运行或撤权窗口已验收。任务11.3完成后进度为**55/63**。
