# 独立隔离 RAG 本地实施验收

## 本轮状态（当前构建已通过，真实运行验收继续执行）

本轮仍为 `extract-isolated-rag-service` / `dev-fix`。本文件将**本轮交接事实、待执行验证和历史原始记录**分开；下方 `74c8d92` / `ee4c9b9` 的测试、失败、命令、JAR hash、任务统计及当时阻塞原样保留为历史，不证明当前实现通过。任务重开不是退步，主会按最新证据维护计数；本编辑不改 tasks、不重算/补勾任务，也不把旧32/49或683项写成当前验收。

本文件由主会整合当前源码、构建日志与 Docker 代理实证，历史文档编辑代理的只读/范围约束不是当前实施限制。已读取安全 fresh/backend/Nacos/script/restore JSON 摘要，均与本轮命令结果分开记录。备份内 `runtime.env`、inspect/env/dump 私密内容严禁读取或打印。

### 已授权与已明确的验收口径

- 个人开发环境、本地/本项目容器可变更。公司 embedding/rerank/LLM 为预定目的地，不要求新脱敏项目或重新批准；范围决定不是模型/协议/能力已验证。
- ADMIN 可读所有未删除房源，含非ON_SALE；BROKER仅ON_SALE；MANAGER不默认准入。`rag.scope.v2` 加 `businessWideRead`，房源 policy `listing-v2`；实际作用与跨角色反例待当前测试。
- 个人记忆**所有读、写、回填 ADMIN 亦 owner-only**；失效用户 token 无效。READ最终仅真实USER，common signer拒SERVICE READ，领域删不可达SERVICE public分支，实际消费者无fallback。上述是主会交接的源码修订，不是已通过测试/部署。
- KI-79 的实际缺口是内部 artifact/by-id（省略sessionId）、handoffs/by-task、workflow/history 未按持久session owner限定，以及缺session的后续写入未拒绝、最终输出只使用入口缓存身份。controller已定点补齐并加回归，尚未执行。既有session service已拒绝owner reassignment且session_id唯一，不能据controller前置缺口声称已证明会话接管。
- A2A最小真实SDK transport patch/auth beacon已授权；仓库模块 `a2a-sdk-transport-patch` / `0.2.5.Beta2-bk-transport1`，原binary unpack、两个选定官方source加patch compile，不改`.m2`、不反射、不第三JSON-RPC。`rag.credential.v2`兼token/beacon，当前SDK路径和凭据生命周期待验证。
- 直接删除无消费者 `AgentRpcService.syncListingKnowledge(Long)` / `deleteListingKnowledge(Long)` 和无消费者signed listing legacy reconcile，不建旧兼容周期/成功代理。**正常REST/工具/业务READ/生命周期/outbox/回填与worker物理hash reconcile保留**；删除完整性和正常入口回归待当前tests。
- HTTP仅`mode`/`allowed-hosts`/`internal-networks`：PUBLIC_ONLY默认，精确公开host `open-chatgpt.ke.com`；MANAGED_INTERNAL精确host对应RFC1918/ULA，项目Docker `172.24.0.0/16`。`host.docker.internal`容器 `192.168.65.254/32`、Windows `10.117.102.54/32`用不同map。全raw DNS/实际地址绑定/重连/TLS及proxy/redirect deny保留；HTTP不覆盖Milvus gRPC/Dubbo。
- supervisor.knowledge未注册/未激活，未知语料不公开、不新建语料；营销独立ES在范围外。旧LR/KI中待授权SDK/私网一律拒绝/旧RPC兼容周期等内容保留历史，权威清单和spec同步由主会处理，本编辑不改它们。

### 实际初始化、备份与最小权限（Docker代理交接）

| 证据 | 本轮已确认摘要 | 限定范围 |
|---|---|---|
| 原MySQL | `8.4.11`；四库受限dump `168764` bytes | 未读取payload，不代表ES/Milvus备份/迁移或恢复已验收 |
| 原Nacos | 十二原config，在`BK-AGENT`/`public`各六，原无rag | 不披露config内容/凭据 |
| Nacos快照 | docker commit pause snapshot + docker save archive `293634560` bytes，有archive checksum | 隔离internal网络实例恢复12原config/hash成功；crash-consistent，**非cold Derby** |
| 原运行资源 | Nacos `978571137a06`、MySQL `325cc757feb7`未重建；根`.env`未改 | 不表示新应用部署 |
| 初始化manifest | `v20261013.1`，fresh九步/upgrade六步，固定DB/SQL、LF SHA-256/shared runner+check | 不依据旧脚本说明声明真实SQL全未执行 |
| 升级失败保留 | 首次checksum因CRLF未归一，在DDL前拒绝 | 不能抹除失败或说第一次即成功 |
| 升级修正 | LF修正后六步actual twice Verified | 不是当前应用回归/默认检索 |
| 原计数保持 | listing0/preference0/constraints17/shared memory3/users2/roles3/workflows3/checkpoints46 | 不代表旧索引为空/水位已追平 |
| runtime权限 | `rag_runtime`仅两个`bk_rag`控制表SELECT/INSERT/UPDATE，无DDL；跨`bk_auth` SELECT实测1142 | 不等于角色scope/个人owner权限验收 |
| 独立fresh | `bk-anent-dev-initcheck`，新空卷`bk-anent-dev-initcheck_fresh-data`，首启healthy九步；fresh重跑两次各九步，upgrade再六步均check | 原卷未重建，非回填/应用运行 |

安全备份根为 `D:/project/rag-dev-backups/42a55bf-20261013-init`，含秘密文件，只引用非秘密摘要。本编辑未独立重算dump/archive checksum或读取原始SQL日志。

**已读取的fresh JSON范围**：`fresh-init-verification.json`记录容器`bk-anent-dev-initcheck-mysql-1`、项目/保留新卷、manifest、healthy、replays=[fresh,fresh,upgrade]、counts_unchanged=true；auth users2/roles3、contract template1、settlement rules2/tier1、memory lock1；listing/preference/constraint/shared memory与RAG document states/index events均0。history facts auth/interview/agent/rag/listing/memory为1/2/3/1/1/1，合计9；active_admin_broker2/auth_other_user0。JSON未附各步SQL原始输出或checksum值，不说成本编辑独立运行全部check。fresh约束0与原环境约束17属于不同环境，不覆盖或抹去原数据。

### 七配置发布的逐时点事实（不混同应用生效）

七选中模板为rag/auth/listing/memory/agent/customer/marketing，已接READ/A2A密钥与managed HTTP。Docker实测`bkagent`登录HTTP200，顶层accessToken未回显；auth发布/readback成功。rag POST true后立即GET404，是新配置异步可见性观察；采用限定deadline readback retry，不能将上传响应当最终生效证据，也不抹历史404。

最新实证：**11域key ID/env扩展后，七模板再次认证发布且全部readback SHA-256一致，Nacos未重建**。主会已读取安全 `nacos-publication-verification.json`：namespace=BK-AGENT、group=DEFAULT_GROUP、authenticated_login/readback_equal=true、a2a_domains=11。只七dataID，不整目录；应用实际读取和运行身份仍未验证。

env保留`{DOMAIN}_A2A_KEY_ID` / `{DOMAIN}_A2A_SIGNING_SECRET`；response为`AUTH_RAG_RPC_RESPONSE_KEY_ID` / `AUTH_RAG_RPC_RESPONSE_SECRET`，不用新的AUTH前缀。其他六app YAML扩展保留九域真实guard能力、非全拒，六服务未部署；模板扩展不等于部署验收。密钥不回显、不写文档。

### 当前新增验证记录

- 全仓 `git diff --check` 通过，仅 Git LF/CRLF 提示。现存严格 YAML parser 非零计数校验16份 application、2份 Compose、17份 Nacos共35份，explicit duplicate key 检查通过；只证明解析，不证明配置运行生效。
- 原 `mysql-before.sql` 已恢复到独立空卷 `bk-anent-dev-restorecheck_restore-data` / `bk-anent-dev-restorecheck-mysql-1`；允许读取的 `mysql-restore-verification.json` 证明 original_counts_match=true，约束17/shared3/auth2/roles3/workflows3/checkpoints46。没有恢复/覆盖主库。随后主会读取 `nacos-restore-verification.json`：隔离internal网络实例ready、12原config逐hash匹配，无有效host端口/mount，原Nacos未修改；第一次network-none实例因无非环回接口在Derby前失败且保留证据。证明paused快照crash recovery，不是graceful cold备份或当前应用/config/事件水位回滚。
- 当前第一次 offline reactor compile：common编译成功，SDK patch module 因 antrun/plugin 本地缓存的 repository ID 不属于当前 aliyun-public 而失败，后续模块 skipped；未改 Maven settings 或缓存metadata。
- 正常 online reactor compile 下载/验证所需plugin与official source后，SDK patch 两个官方 source成功生成并编译；common-skill通过，common-a2a失败：`A2aServerHandlerAutoConfiguration` 类不存在、`A2aAuthenticationToolInterceptor` 缺 `getName()`。定点API修复中，剩余模块 skipped。这不是完整compile通过，更不是tests通过。
- 第三次 offline compile：SDK/common-a2a/auth通过，agent 的 Graph checked Exception 不符合 Supplier 类型而失败，下游 skipped。定点改用官方 NodeAction，保留原异常传播及 lease 自动恢复。
- 当前 `clean compile`：22模块全部 BUILD SUCCESS，日志 `D:/project/rag-current-reactor-clean-compile.log`。此前三失败日志仍保留：`rag-current-reactor-compile.log`、`rag-current-reactor-compile-online.log`、`rag-current-reactor-compile-fixed.log`（均在 `D:/project`）。
- 当前首轮 reactor test：common32、common-skill54通过；common-a2a35项中1 error，撤销凭据测试 anySatisfy 对 WORKING 事件的空 TaskStatus.message 解引用。实际 SDK 专用 transport 的302/307同步及SSE不转发测试通过，其余下游未执行。正在定点核对/修复事件断言，不能报完整测试通过；日志 `D:/project/rag-current-reactor-test.log`。
- 先前 Python YAML 检查因缺PyYAML失败；独立Java validator两次被安全hook拒绝，均不作通过证据，改用已可用严格parser完成只读校验。
- 主会已读取安全 backend JSON：旧 ES green，listing_info/marketing_content 各0文档且mapping为空对象，保留原UUID/hash；Milvus默认库空集合且精确catalog仅1库、0collection/partition，旧模型/PK/schema身份不存在，不能拿配置模板补造。四旧后端先只读归档停止卷再启动原容器，没有删除旧存储。脚本实际 mysql+nacos状态成功，缺rag/停止auth均非零，错误host超时124、实际本地MySQL0；不等于当前应用或默认RAG能力已验证。
- 第二轮 `-fae` current test：RAG279、auth60、common32、common-skill54、gateway5、promotion2全部通过；common-a2a37中redirect loopback夹具I/O error、memory55中独立security mock未stub当前认证error、customer4中null默认值/抛错mock重新stub共1 failure/1 error，下游依赖模块skipped。同步及流式撤销测试11项通过。实际修复保留安全断言，不放宽生产授权；日志 `D:/project/rag-current-reactor-test-fixed.log`。
- redirect夹具完整消费HTTP request body后，当前A2A定向test BUILD SUCCESS（`D:/project/rag-current-a2a-focused-test.log`）；完整reactor仍需重跑。
- `model-preflight-verification.json`：仅合成非个人文本，旁路公司embedding HTTP200、返回模型与配置一致、dimension1024且全finite；未经过应用HTTP guard。现有RAG rerank caller key在models验证返回401，未发送rerank正文、未读外项目secret或修改外项目；profile仍null，不能声明默认检索通过。

### 2026-10-14 当前配置与定向回归

- 用户补齐配置后重新认证读取 `BK-AGENT/DEFAULT_GROUP` 的14个精确 dataID，全部HTTP200；13个与当前模板一致，只有rag模板仍为先前发布内容。live三个rerank-profile为null且缺governed身份钉扎、新host/CIDR；根环境已具备新的非空caller key、governed端点及身份字段。历史备份runtime不是最新发布来源，不能把其空key继续报告为当前根配置缺失。
- 专用 `bkanent-rerank-dev` 已存在，internal网络 `10.253.240.0/24` 仅有外部reranker；本项目现有运行容器尚未接入。当前宿主governed请求DNS失败，现有项目容器getent退出2/curl退出6/HTTP000。这是当前部署网络路径未接入，不是401、模型协议失败或经过应用guard的成功证据。外部既有能力实测只作历史身份依据。
- 记忆域 `memory.rag.semantic-scoring-versions` 已有application空默认，但Nacos及Compose漏透传；已补齐同一环境变量且维持空集合失败关闭。发布只明确注册当前三个governed profile的精确scoringVersion，不引入通配或自动接受。见KI-86。
- Agent index测试先因AutoCloseable隐式close的检查异常无法编译；加throws后实跑暴露循环重新`when`触发旧Answer，对matcher空payload求hash失败。改为`doAnswer(...).when(...)`，保留请求签名、USER/actor/token、ACCEPTED成功与STALE/CONFLICT拒绝断言。最新定向9项通过，日志 `D:/project/rag-agent-current-focused-fixed.log`；先前失败保留 `rag-agent-current-focused.log`。
- 九服务技能装配测试使用各自独立的专用测试lease，并增加无lease时零模型/零事件认证拒绝；保留真实服务组件、技能发布、显式正文、default完成、版本不符零新增模型调用。当前定向138项通过，包括九服务、官方SDK重定向、执行撤销、managed HTTP及governed重排；日志 `D:/project/rag-current-auth-wiring-focused.log`。这是本地mock/loopback回归，不是实际Auth/Dubbo/模型/容器验收。
- 当前22模块源码冻结后串行`clean test`在agent模块失败：生产版本SQL显式`USE bk_agent`，H2夹具未创建该schema，三编排类共34个error；package及制品证明因前置失败未执行。日志 `D:/project/rag-current-reactor-clean-test-20261014.log`。正在定点适配夹具，不删除真实MySQL目标库/修改已应用manifest；见KI-87。选择器仍显式排除下述七外部联调类。
- 当前A2A定点审阅核对官方SDK源发现两项生产缺口：push实际字段为`pushNotification`而非guard检查的`pushNotificationConfig`；同owner重复send在executor拒绝前，官方handler可能已更新TaskStore/队列/runningAgents。已安排guard早拒绝及原子在途预留的最小修复和回归，未以先前绿色测试声明不存在这些问题；见KI-88。
- 独立串行`-pl memory-service,rag-service -am test`通过：common32、memory58、rag288，合计378项，0失败/0错误；包含新增评分配置3项、owner/controller/生命周期及当前governed/managed HTTP回归。日志 `D:/project/rag-memory-current-test-20261014.log`。不含尚在修复的agent编排及common-a2a增量，不是完整reactor通过。

- 2026-10-14 Docker代理已限定备份旧live rag/memory，仅发布两份当前模板并认证读回一致：rag LF SHA-256 `58a3963bfd3b1bf3c0bc12dae6a6cfb53815cbf9f142ab70406481c248a33a3c`，memory `88b41e0b6309e0fd60c5f1f0c88ccb55997ff86f7bd21f506e3a5d3484093e6d`。受限阶段目录 `D:/project/rag-dev-backups/42a55bf-20261014-release-prep` 不读取私密payload；未构建或启动新应用。
- 当前实际MySQL固定账户事实确认 `rag_runtime` 零行，不应将1045继续归因为旧密码错误。原账户未重置；单独创建 `rag_release_20261014` 并通过受支持driver参数绑定秘密，仅两个控制表SELECT/INSERT/UPDATE，实际项目host登录及两表SELECT通过、跨`bk_auth.user_account`拒绝1142。实际host映射为 `10.117.102.54:3306`，不是根配置的127.0.0.1；根及历史环境未改。
- 五旧app（agent/listing/memory/customer/marketing）逐源docker commit均因containerd缺失同一原blob失败；无镜像tar/save成功，原停止容器全部保留。不能宣称完整应用回滚，未转export/import或删除源容器。后续仅独立新release实例，不重建或覆盖这些原实例。
- 新A2A guard/lease定向测试12项中1error：首次实际stream handler转交被包装为AUTHENTICATION_FAILED，另11项通过。精确cause为测试JSONRPCHandler传null AgentCard，SDK在stream try块前读取capabilities触发NPE；仅换真实streaming-capable Card，生产不放宽。原失败日志 `D:/project/rag-current-repair-focused-20261014.log` 保留。独立编排Maven Store4/Reconciler2/Graph30共36项全部通过，日志 `D:/project/rag-orchestration-repair-focused-20261014.log`。新增领域单房源SQL后与memory最终writer锁等待后重认证修复和7项回归已冻结，见KI-89，当前串行复验中。

- 单独串行领域输出回归通过：ListingBusinessAuthorizationTest7项、MemoryRagSearchFacadeTest24项，共31项，包含KI-89新增7项。日志 `D:/project/rag-domain-output-focused-20261014.log`。第二轮A2A测试失败保留：真实stream Flux未消费，官方producer等待poller启动，故awaitTermination失败及相同测试task静态污染；生产预留仍正确保持，夹具改为消费真实Flux并发终态和唯一task，不放宽租约。

- 最终common-a2a定向36项通过，包含真实SDK push配置拒绝、在途重复send/stream零store/queue副作用、转交失败/拒绝dispatch和两侧完成后释放、官方transport重定向拒绝及同步/流式撤销；日志 `D:/project/rag-a2a-repair-final-focused-20261014.log`。
- 当前22模块串行 `clean test` 和 `package` 均 BUILD SUCCESS（2026-10-14 12:42／12:44 +08:00）。每轮按137份Surefire XML统计 **848项**，0失败／错误／跳过，19个模块具有测试；日志控制台同时含逐类和逐模块汇总，不能把重复的1696计为独立测试。七个外部联调类仍显式排除：ApprovalClaimDatabaseConcurrencyTest、SupervisorDockerDatabaseTest、SupervisorDistributedAcceptanceTest、NacosAgentRegistryDockerTest、NacosRollingUpgradeDockerTest、SupervisorRealModelEvaluationTest、LiveSkillAgentCardsDockerTest。日志为 `D:/project/rag-current-reactor-clean-test-final-20261014.log` 和 `D:/project/rag-current-reactor-package-final-20261014.log`。
- 制品核验 exit0：14个Boot JAR、唯一patched SDK嵌套包和hash、两份官方source精确重建、其余9个官方binary class逐字节一致、LICENSE、无重复ZIP条目及后端依赖隔离通过。源LF指纹 `4e5307ef818d1263896ced5f78ea872e69523e87903cc156bc1ee833d40f476d`，1213个输入逐项可核对；SDK SHA-256 `15ec8c131773bdfd695b6be6febbcc46f47792ff3e7aea74965f67a77e11456c`。Java17私有probe针对当前Agent包编译通过，尚不等于运行。仓库外报告 `D:/project/rag-current-artifact-verification.json`，日志 `D:/project/rag-current-artifact-proof-final-20261014.log`；原javac stderr解码线程报UnicodeDecodeError，编译return0不变；复验先因误查根目录class而断言失败，修正实际包路径后在新目录字节捕获编译exit0／class存在，保存 `D:/project/rag-probe-compile-safe.json`，未修改Java源或抹去失败。
- KI-90原broker主因已只读闭合：rocketmq运行uid/gid=3000:3000，store卷根0:0／0755不可写，commitlog／consumequeue创建失败及store/lock Permission denied；shutdown NPE是次级错误。实际stop一次／仅原卷根chown3000:3000／同原broker start一次成功，0755、id／mount／restart policy不变、无数据删除／读取。TCP10911 ready、真实mqadmin clusterList exit0且broker-a注册，running／restart0／OOMfalse；safe证据为release-prep内broker-permission-repair-safe.json及broker-after-repair-health-safe.json。日志仍有store_failed分类，需区分早期缓冲，不能称所有日志无错；协议成功不等于Agent事件总线验收。七个独立新release镜像在验证JAR／全部源指纹一致后开始构建，不覆盖旧实例或tag；首auth镜像RUN exit1，失败记录 `D:/project/rag-dev-backups/42a55bf-20261014-release-prep/release-build-failure-safe.json`，原marker只匹配组合命令文本；后续实际JAR checksum明确OK，真实java --list-modules确认命名两模块存在但jdk.compiler缺失（固定digest是JRE，不是JDK），source-mode健康检查需要compiler而失败。早期java17-runtime-module-safe.json按字符串contains误报compiler，已由runtime-actual-module-predicates-safe.json覆盖；保留原误判，不作通过证据。限定修复为已编译Java17健康检查class-mode，probe另用实际JDK，尚未启动新app。

### 2026-10-14 已推送制品与独立实例运行增量

- 实施commit `8fed832` 已推送 `origin/dev-fix`，本地与远端完整HEAD均为 `8fed83219238cadfe80d47ed3ea1752d56a1f38d`。192个路径经限定staging核对，174个执行／配置输入与上述构建证明一致，未纳入私密env／缓存／生成SDK源。commit／push hook均返回scanner_enobufs，没有完整安全审计结论，不宣称项目安全。
- 受限stage预编译ReadinessCheck.class（Java17 major61）并改class模式，实际配置校验exit0；应用JRE仍固定ec72…digest，仅保留真实命名模块门禁。独立probe JDK固定 `maven@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc`，实际java／javac17及命名／compiler模块通过；没有运行Maven。见release-class-health-and-probe-jdk-safe.json，KI-91。
- 六个独立镜像（auth／rag／listing／memory／agent／customer）已build exit0，source／JAR label逐项相符，运行UID10001:10001；marketing仍待build结果，不覆盖旧tag。见release-images-class-mode-built-safe.json。
- 首批实例暴露数字UID无home，Nacos／Dubbo试写`?`目录失败；仅stage显式user.home=/app/runtime，复用10001:10001／0755可写tmpfs，rootfs只读／no-new-privileges不变。Auth／RAG／Memory实测healthy／running／restart0；Listing仍在发现验证，不将三服务健康当七服务完成。见release-initial-start-failures-safe.json、release-four-current-health-safe.json，KI-92。
- 原有两个ADMIN／BROKER经参数绑定SQL核对ACTIVE／非删除／角色后，实际 `/auth/login` HTTP200／code0／角色相符，`/auth/me` HTTP200成功；未改密码、账号或撤销原用户，所有token／密码仅受限private环境，未输出正文。见actual-seed-auth-safe.json。这是正向登录事实，不代替无效用户／个人owner反例矩阵。
- RAG双网卡默认注册10.253.240.3:20898使仅bk网络Listing不能访问；仅stage RAG固定已核对唯一bk IP172.24.0.12，并设置官方bind／registry变量同值，新RAG重建exit0，实际IP一致、rerank网络保留。见release-rag-registry-stage-fix-safe.json，KI-93；真实Nacos发现／消费者连接待验，直连probe不代替发现成功。

### 2026-10-14 首轮真实运行暴露的当前修复

- 七个首轮镜像实际build成功；auth／rag／listing／memory／customer各实际readiness HTTP200且healthy，Agent额外DashScopeAgent自动配置要求未选提供方key、营销两个必要RPC配置默认Bean名冲突导致启动失败。两个新失败实例各精确stop一次exit0，ID／image／mount／restart policy不变，旧app与共享infra未碰；见release-first-wave-acceptance-safe.json、new-failing-instances-stop-safe.json。
- 真实Listing最早异常帧闭合Nacos discovery事件刷新invoker，经官方RestProtocol和OkHttpClientFactory首次加载okhttp3.Callback而缺类。官方A2A SDK client无该引用，原依赖全部保留；仅common-a2a补共享Dubbo运行依赖，不给SDK patch添加无关库。见listing-first-callback-exact-frames-safe.json、KI-96／LR-44。
- 修复额外未使用的DashScopeAgent自动配置和营销Listing配置Bean名，保留DeepSeek／Graph／A2A及两个必要RPC桥；定向Agent1＋营销11共12项通过，日志rag-runtime-boot-repairs-focused-20261014.log。共享模块四项无网络JUnit通过，含真实Dubbo OkHttp链接／构造、Java17 Fastjson2 principal round-trip、实际probe编译及query拒绝，日志rag-runtime-dependency-focused-20261014.log；不是实际签名ADMIN成功。
- DOMAIN初次exit127由Windows误选WSL bash导致，未进入Java；改为绝对Git Bash后真实Auth TCP成功、权限RPC未完成，原因是裸直连URL缺prefer metadata退回Hessian2，Java17反射失败。仅probe固定serialization=fastjson2并拒原query，不add-opens、不绕Auth。真实signed principal／INSPECT／PROVISION尚未通过，见KI-97。
- RAG Dubbo注册及Listing连接已实际验证172.24.0.12:20898；HTTP discovery仍10.253.240.3:9018，下一次仅stage修正并验证，KI-93保持OPEN。只读实际ES三个新索引404、Milvus collection list HTTP200／code0且0集合，无PROVISION／schema写入；实际容器DNS匹配host maps不是guard验收。
- 本轮执行源／POM／probe改变后曾重开8.7为27/49；冻结后22模块串行clean test及package均BUILD SUCCESS，各855项、140份XML、0失败／错误／跳过，19个有测试模块。新增7项为Agent1、营销2、共享运行4；日志rag-runtime-repairs-clean-test-20261014.log、rag-runtime-repairs-package-20261014.log，七个外部联调类仍按上述选择器显式排除。当前1216个源输入LF指纹977fd9b2ba50968fcd7dfcbd626502ab15c9cecc51412c7f4d610ea9376b188e；14Boot JAR、唯一SDK／两个源精确还原／九官方binary一致／license／后端隔离、Java17实际probe编译全通过，十个实际A2A消费者运行包都具唯一OkHttp4.12／Okio-jvm3.6／Kotlin1.9.25。报告D:/project/rag-runtime-repairs-artifact-verification-20261014.json，日志rag-runtime-repairs-artifact-proof-20261014.log；原报告／目录未覆盖。8.7重新关闭为28/49，不证明真实身份／模型／五操作。worker／两个dispatcher仍false，是有意门禁而非缺陷。

### 2026-10-14 重新发布与执行修复收尾

- 7bd7a52及977fd9源证明对应七个不可变镜像全部构建，六服务healthy／restart0／readinessHTTP200，Agent进一步因未选AudioSpeech模型缺key失败并精确停止。旧五容器、首批七镜像、根及历史环境hash全部保留。证据repair-wave-stable-state-preservation-safe.json，实际Agent历史restart_count最终4，不沿用中间快照。
- 当前RAG业务地址为172.24.0.20；并行重建时.12被新Memory自动分配，故仅RAG固定地址及官方Dubbo／HTTP发现统一.20。认证Nacos实际HTTP9018、Dubbo20898和三RPC均.20且healthy/enabled；见repair-wave-nacos-discovery-safe.json，KI-93地址故障关闭。未改变其他业务网或将消费者接入rerank网络。
- 三空间冻结INSPECT均经真实signed ADMIN与操作后复验，ready=false／dimension1024／四阶段失败，独立诊断确认签名链。偏好EMBEDDING为ENDPOINT_POLICY_DENIED，其他EMBEDDING及全部KEYWORD／VECTOR／RERANK为DEPENDENCY_UNAVAILABLE。不能把非就绪当鉴权失败、不能统一归为缺schema。未PROVISION／backfill／enable workers；见repair-wave-domain-inspect-safe.json。
- 实际同JRE／同网络JNDI组合A+AAAA三个host均超时，而各自A和AAAA查询成功；官方JDK将组合转换为ANY。源码已改同context分类型查询及整体deadline，保留全部原始地址校验和pinning；新增16次JNDI facade回归通过。Agent一次排除九个未选默认DashScope模型，保留DeepSeek／Graph／A2A及AsyncToolCallingManager；两模块定向共89项通过，日志D:/project/rag-dns-model-focused-20261014.log。不宣称新制品运行通过。
- Listing同名HTTP9018被官方SC-only通知REST通配适配，而真正provider为Dubbo20898；消费者不指定协议可误匹配。限定纯Dubbo consumer.protocol，保留HTTP发现／provider／group／REST SPI及序列化。错误归属与标准修复见KI-100／LR-45；不再重复DNS或provider根因调查。新执行修复使8.7暂重开，855项历史证据原样保留。

### 2026-10-14 Listing装配与INSPECT期限增量

- Listing identity／retrieval／index三处ObjectProvider限定既有显式桥名，保留Dubbo字段产品、原AuthPermission独立接口及所有签名／缺桥失败关闭。实际Distributed配置和生产消费者的双候选Spring上下文新增两项回归；定向ListingRagIdentityTest五项及OfficialRuntimeDependenciesTest四项共九项通过，0失败／错误／跳过，日志D:/project/rag-inspect-wiring-listing-probe-focused-20261014.log。
- 实际INSPECT consumer timeout是30000ms；不盲改全局等待值。新probe仅在调用边界打印固定接口／method／protocol、configured timeout与numeric elapsed／safe code／remoteOutcome，异常原文不打印；stage/code必须经操作后signed ADMIN复验才输出。超时不是远端取消证明，返回也不是ready或领域发现成功证明。
- RAG共享整体期限／固定阶段顺序／有界线程及队列／实际worker退出租约／迟到证据隔离、ES HEAD与mapping可取消读取、Milvus单次schema查询及实际SpringAI模型单次无退避接线已冻结。定向37项通过，含真实官方自动配置在离线503下仅一次请求及凭据空时lazy/base启动；原37项中一个Mockito重复设桩触发旧Answer的失败日志保留，改doAnswer后全过。新日志D:/project/rag-inspect-wiring-rag-focused-fixed-20261014.log；Listing／probe九项另验，共46定向项。8.7因新执行输入暂重开，c338b7e的886项历史证明保留，不沿用为新增源码验收。
- 7.3纯部署交付已完成独立复核：仓库Dockerfile模块／JAR选择、Compose独立RAG9018／20898和Memory20891表达齐备，config-q及指令／shell语法通过，受限release实际镜像／注册已验，未改变外部reranker。标准Dockerfile实际Maven rebuild未执行，双网络标准路径注册地址仍须实际验证，不能盲抄stage占用中的.20。本项闭合不替代8.2／8.3；安全证据task73-repository-delivery-summary-safe.json，当前8.7重开后29/49。

- 本增量完整串行clean test及package各896项／142份Surefire XML，22模块BUILD SUCCESS，0失败／错误／跳过；七外部类按既有明确选择器排除，并未执行。日志D:/project/rag-inspect-wiring-clean-test-20261014.log与rag-inspect-wiring-package-20261014.log。14Boot JAR、九官方未改class、两个官方source精确重建、license／后端隔离、十共享运行库及当前probe Java17编译均通过。新证明D:/project/rag-inspect-wiring-artifact-verification-20261014.json，SHA-256为1a0f9332795d6f51ba2a21de8abe2f4566caf82d5a26b007eccf60ef352f5c31；1218输入LF指纹22f9fe2a579d98f15937ca31b207cb909354348eb2c84f4852f228c163fde736。8.7重闭合后30/49；源码验证不关闭KI-101／102运行边界，真实调用继续，未PROVISION或enable worker。

### 本轮仍待完成的验证

1. 当前DNS／模型／consumer协议修复完整回归与package均通过，各886项／142份XML，0失败／错误／跳过；14Boot JAR、唯一SDK／两源精确重建／九官方类／license／后端隔离／十共享运行库／Java17私有probe编译通过。源LF指纹4356c418965cfe851cdbdc118ae672ec7416b62beed20b4a9f6e7c4ac98a0cd5，1218输入（1214已核对提交执行输入及四个ignored IDEA XML），当前证明D:/project/rag-dns-protocol-artifact-verification-20261014.json；日志rag-dns-protocol-clean-test-20261014.log、rag-dns-protocol-package-20261014.log、rag-dns-protocol-artifact-proof-20261014.log。8.7重新关闭为28/49，下方855／848／683有明确源边界；七外部联调类仍显式排除，实际Auth／模型／五操作不能据构建补勾。
2. c338b7e2d06016a8adca4e7d1e184b36e5ff502d已推送origin/dev-fix，本地及远端HEAD一致（首次TLS连接失败重试成功）。七4356c4新镜像全部build0、source/JAR标签匹配，实际七服务running／healthy／restart0／readinessHTTP200顶层UP，Agent未选模型故障关闭。当前RAG .20 HTTP9018／Dubbo20898及三接口注册healthy；旧14镜像和release环境值／策略保留。当前三空间仅初段signedADMIN通过，INSPECT随后RpcException→TimeoutException／DEADLINE_EXCEEDED，无能力结果或DOMAIN后身份复验；真实Listing调用另在鉴权出现NoUniqueBeanDefinitionException。新故障KI-101／102继续修复，不能声称发现消费者实际调用或保护模型成功。安全证据dns-protocol-health-discovery-safe.json、dns-protocol-domain-inspect-safe.json、dns-protocol-domain-http-error-safe.json、dns-protocol-release-gates-preservation-safe.json；未PROVISION／workers。
3. 公司模型真实调用、provider/model/version/dimension、rerank协议/scale/profile/scoringVersion、ES/Milvus真实schema/身份/权限、默认双路+融合+rerank及两路硬过滤等价。
4. owner-aware回填、revision/hash/schema/tombstone、APPLIED及实际两后端物理水位/删除结果、迟到写/partial补偿、领域最终排序与真源复核。
5. stop-write/drain、增量追平、实际消费者切流、知识关闭零RAG调用、RAG outage下正常业务可用性、事件水位rollback及备份恢复演练。不建旧兼容周期，但不豁免这些运行门禁；不删除旧index/collection/volume、不prune/down-v。

历史七外部联调类的显式排除和排除时的原因仍保留，不把新授权当它们已运行通过，也不把历史选择器中的0 skipped当全外部覆盖。任何新构建选择器/失败/排除必须另有当前记录。本轮不发起完整深度安全扫描，不以初始化或发布摘要声明完整安全审计、默认检索或切流通过。

实施方已授权的继续执行步骤见[执行与剩余门禁](isolated-rag-unblocking-steps.md)，逐空间SQL/身份与水位事实见[迁移清单](isolated-rag-migration-inventory.md)。可执行工作由实施方推进，不交还用户或重复要求批准。

---

## 历史原始记录（以下为前序时点，非当前验收状态）

以下原命令、失败/修复、测试统计、JAR hash及任务边界按历史保留。文中的“当前/本次/未授权/未执行/仍阻塞”等措辞仅指各段当时，不能覆盖上方本轮状态；32/49及剩余17项是历史复核，真实SQL全未执行、SDK待授权、旧成功代理/兼容周期等旧说明不是当前门禁。`ee4c9b9`的683项及四JAR hash未对新HEAD/并行修改重跑。

- 变更：`extract-isolated-rag-service`，分支 `dev-fix`。
- 本记录区分源码/本地测试验证与真实运行迁移；不构成上线、安全完整审计或真实默认检索验收。
- 前序公共契约及 auth 实施提交：`2a3fa12`；后续迁移实施及本地验收提交：`74c8d92`，已推送 `origin/dev-fix`。历史 `split-rag-by-domain` 不实施、不归档或改写。
- 当前本地实施任务完成 32/49；其余17项含真实环境/旧数据/身份传输/旧调用证明/切流门禁，保持未勾选。本变更未归档。ee4c9b9实施后的30/49是历史统计；本次按原任务边界独立复核补勾7.2、8.1，详见“任务完成边界复核”。

## 实施边界

`rag-service` 集中 ES 关键词召回、Spring AI Milvus 数据读写及 embedding、RRF 去重融合和 BGE native/DashScope 语义重排。域通过 Dubbo 的版本化契约提交受控参数，不能自行选择物理目标或执行某一路。不同空间禁止共用 ES 索引或 Milvus 集合；注册隔离不替代真实用户、服务身份和领域权限。

房源与记忆保留 SQL 真源、语料、授权、生命周期与最终排序/复核。变更在同一领域事务持久化 revision/outbox，提交后发送完整投影；RAG 独立 `bk_rag` 保存索引控制状态，旧版本/部分写/删除命中在融合前不可见。最终领域响应的所有候选/正文均再核对真源，stageStats 不透传。只允许短暂漏检/延迟，不承诺跨后端原子事务或 exactly-once。

原 agent/listing 的本次后端执行类、bean、初始化和执行依赖已迁出；agent 保留 chat/Graph/A2A、旧 REST 和只读工具的纯包装，marketing 的独立 ES 业务搜索不属于本次提取。系统约束 bootstrap/YAML 仅在 memory 执行且仅补缺。Supervisor 历史快照只读、已完成调用不重发，知识工具首终态结果不能被迟到结果覆写（LR-37）。

管理操作同时要求服务/空间操作许可、USER、真实 signed principal 和 adminRoles；SERVICE writer 不能管理或搜索。凭据不进入模型参数、普通上下文、日志、outbox 或 checkpoint；异步凭据使用有界进程内租约，到期/重启需重新认证。

## 本地验证命令

在仓库根目录，Java 17 / Maven 3.9.16，均使用仓库 settings，离线运行。构建串行，期间无源码代理并发编辑或构建。

```bash
mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml clean compile

mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml test \
  '-Dtest=Test*,*Test,*Tests,*TestCase,!ApprovalClaimDatabaseConcurrencyTest,!SupervisorDockerDatabaseTest,!SupervisorDistributedAcceptanceTest,!NacosAgentRegistryDockerTest,!NacosRollingUpgradeDockerTest,!SupervisorRealModelEvaluationTest,!LiveSkillAgentCardsDockerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml package \
  '-Dtest=Test*,*Test,*Tests,*TestCase,!ApprovalClaimDatabaseConcurrencyTest,!SupervisorDockerDatabaseTest,!SupervisorDistributedAcceptanceTest,!NacosAgentRegistryDockerTest,!NacosRollingUpgradeDockerTest,!SupervisorRealModelEvaluationTest,!LiveSkillAgentCardsDockerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

openspec validate extract-isolated-rag-service --strict
git diff --check
```

显式排除七个真实外部联调测试，原因是继承环境可触发真实 MySQL 写入、Nacos 发布或模型 HTTP；本次不具备这些运行操作/目标的授权及安全前置条件。排除项不是已验收或测试内 skipped 的零值证明。其他 H2、mock 和 loopback 协议夹具正常运行，不宣称无 SQL 或无本机 socket。

## 回归覆盖

- 公共契约/认证：canonical hash、签名绑定、expiry、角色/账号失效、伪造身份、独立 response key。
- RAG：空间映射/权限交集、实际 Spring AI 过滤转换、数值精度/UTC 时间编码、两路 RRF/去重/稳定序、评分 profile 与重排协议、有界 STRICT/ALLOW_DEGRADED、deadline/取消、认证及 URL 安全拒绝不可降级。
- HTTP transport：81 项本地测试覆盖 HC5/HC4 原 transport 的全 DNS 答案、原始 mapped AAAA、每次新连接地址绑定、禁止代理/重定向、响应预算及实际取消；未访问真实后端。
- 索引账本：21 项 H2 生命周期测试覆盖持久化确认、乱序/幂等/冲突/tombstone、部分应用、不可取消迟到写、实际 hash 核对与失败关闭。
- 领域：SQL/outbox 同事务、所有活跃入口、回填交错、授权/阈值/失效/最新 revision/hash 最终复核、持久化 SQL 表示生成投影、统计/正文不泄漏失效候选。
- 兼容：旧六组 REST、固定别名/schema、默认房源 20+20/最终 10、知识 HTTP 最终 5、工具 topK 合法候选池、显式每路 500+500 保留而重排池封顶 500、非法数量拒绝与有限扩池。
- 编排：真实 Graph 知识开关、确定只读失败、首终态 CAS、历史审计恢复、已完成/legacy claim 不重发、异步租约及不落 token；未新增状态机 transition。
- 真实生产 Spring Boot context 测试：使用 H2、不替换生产后端 bean，发现三份 Dubbo ServiceBean；空密钥/空间下基础 readiness 503，lazy embedding/Milvus 不在启动主动探测。测试专门关闭注册/export，不证明运行环境 RPC 已导出/发现。

## 失败及修复历史

首轮 clean compile/test/package 通过，选中测试 643 项。补充显式召回数量及 Spring AI typed retry exception 的 HTTP 401/403 分类后，第二轮 clean compile/test 通过（646 项），随后的 package 重跑在 memory outbox 测试失败：`expected 2 / observed 1`。

固定时钟复现：H2 TIMESTAMP 精度 6，插入时间 `.123456700Z` 持久化为 `.123457Z`，晚于首次派发 cutoff `.123456800Z`。第一派发实际 eligible=0、PENDING/attempts=0/error=null；显式到期后第二派发才首次提交，得到 TIMEOUT/attempts=1。生产派发正确尊重 next_attempt_at，并无丢事件证据（KI-69）。

仅测试在第一次启用派发前显式到期，保留相同 eventId/revision 与 2/3 次提交断言，新增确定性微秒边界回归；未加 sleep、弱化断言或修改生产时钟/签名。修复后 memory 定向生命周期/派发/搜索/安全/bootstrap 20 项通过，随后重新运行上述完整串行验证。

本机原始日志在仓库外：
- `D:/project/memory-dispatcher-reproducer.log`（预修复确定性失败证据）。
- `D:/project/memory-dispatcher-fixed.log`（定向通过）。
- `D:/project/rag-reactor-verified-compile.log`。
- `D:/project/rag-reactor-verified-test.log`。
- `D:/project/rag-reactor-verified-package.log`。

### 首次实施验证结果（74c8d92）

全 reactor 21 个项目的 clean compile、test、package 均 BUILD SUCCESS。选中测试 647 项，test 与 package 重跑各自均 0 failures / 0 errors / 0 skipped；七个真实联调类在选择器中显式排除，不能称所有真实联调通过。

| 模块 | 选中测试数 |
|---|---:|
| common / common-skill / common-a2a | 30 / 54 / 24 |
| gateway / auth / agent | 5 / 53 / 140 |
| memory / rag / listing-master | 23 / 179 / 23 |
| notification / compare-engine / media-worker | 1 / 5 / 6 |
| marketing-content / promotion / business | 6 / 2 / 1 |
| contract / settlement / interview | 13 / 1 / 81 |

运行依赖树另执行并通过：

```bash
mvn -o -gs .mvn-settings.xml -s .mvn-settings.xml \
  -pl rag-service,agent-service,listing-master-service,memory-service,marketing-content-service -am \
  org.apache.maven.plugins:maven-dependency-plugin:3.7.0:tree -Dscope=runtime
```

原始依赖日志：`D:/project/rag-verified-dependency-tree.log`。四个可执行 JAR 的 manifest 均为 Boot JarLauncher + 对应应用 Start-Class，包含 BOOT-INF/classes。只有 rag 含本次 Milvus SDK/Spring AI Milvus/OpenAI embedding/ES 后端库，三消费者无这些后端实现库；agent/listing 保留由 Graph/共享 SDK 传递的 `spring-ai-vector-store` 通用接口库，消费者保留 HttpClient5 通用 transport，不能把这些名字误判为残留 RAG 执行底座。首次宽泛 JAR 断言误将它们当后端库，已按真实依赖树区分，未删除业务依赖。源码检索也确认三消费者无相关后端 import/EmbeddingModel/ElasticsearchOperations，营销 ES 和 agent chat 保留。

本次最终 JAR（`各模块/target/模块名-1.0.0-SNAPSHOT.jar`）SHA-256：

```text
rag-service             bd1d3e7d97233594187caea428a4f9595b8d625c71f5bdc7142fa2d6d120b22e
agent-service           46f75aa8dbb7e8f9596cab282f4c01aa47b2f6c62ed27518639609d99e817754
listing-master-service  a1dfacd40efe11b565cf26fbc66bf7cd9b8f7d49579815f35e5c3f17aa5656f8
memory-service          827c9e06884bc1d1ee6c032e136979074d0e8e4d04e2ea5cfa973764c9b5b28d
```

严格 OpenSpec validation、diff whitespace 检查通过；最终模板代理离线严格解析 10 个 YAML、35 项选中断言，三部署脚本 bash -n/LF 与 chat/MCP 保留断言通过。最终静态漏接修复涉及 memory 管理角色键/registry、listing 入站 agent key 透传及精确 readiness 文案，仍留空角色/密钥，不表示环境已配置。旧首轮 JAR hash 因最终源码变化不再作为交付证据。

## 继续 apply：有界领域处理与迁移盘点

本轮新增36项测试，不更换架构或启用实际提供方。修复记忆逐候选采样时刻、第二池丢首池与累计召回无预算、最终metadata白名单、null语义版本错误；memory回填改为默认100/最大500的分阶段keyset单页；listing reconcile在同锁事务中复用装配并匹配当前完整projection hash后才推进revision。旧ID-only索引RPC仍失败关闭，不据配置开关补造旧事务/排空证明。新逐空间源码/历史模板盘点见 [isolated-rag-migration-inventory.md](isolated-rag-migration-inventory.md)。

memory原 `POST /internal/memory/rag/backfill` 保留，增加可选cursor/limit。默认cursor=`p:0`，依次扫描偏好源、约束源、持久投影身份；响应为cursor/nextCursor/scanned/hasMore/receipts，每receipt只声明QUEUED。空阶段页仍可能有nextCursor；客户端必须续页，nextCursor=null仅表示本轮扫描结束，不能当跨页原子快照或双后端APPLIED水位。旧返回count不再表示无界全库操作。

### 本轮失败和修复

定向首跑在新同分评分测试抛UnfinishedStubbing：mock Instant的thenReturn参数内计算plusSeconds触发被mock的静态构造。将第二时刻提前构造后，第二跑的全局now次数断言计入H2内部时钟而失败（25次，领域search实际1次）。最终测试不统计数据库内部时钟，保留向后前进的时钟、反向上游同分顺序、两候选精确.36分数及三次重复断言；若退回逐候选SQL后采样，分数会成为.18并被断言拒绝。未改生产clock、加sleep或放宽业务评分断言。见KI-77。

定向最终9个测试类89项通过（agent8/memory39/rag23/listing19）。随后所有代理冻结，串行执行本记录相同的全仓离线clean compile/test/package命令及同样七类外部排除；21个reactor项目全部BUILD SUCCESS。test与package各683项，0 failures/errors/skipped；memory46、rag184、listing31，其余模块数量不变。新增测试包含实际H2事务、Mockito签名/RPC边界和本机夹具，不作为真实MySQL/ES/Milvus证明。

原始日志：
- `D:/project/rag-followup-targeted-test.log`（首次夹具错误）。
- `D:/project/rag-followup-targeted-fixed.log`（全局时钟次数错误）。
- `D:/project/rag-followup-targeted-final.log`（89项通过）。
- `D:/project/rag-followup-reactor-compile.log`。
- `D:/project/rag-followup-reactor-test.log`。
- `D:/project/rag-followup-reactor-package.log`。

四个新可执行JAR的Boot JarLauncher、对应实际Start-Class、BOOT-INF/classes与后端依赖隔离均重新核验。源码/POM依赖声明本轮未变；前三消费者仍无实际Milvus/OpenAI embedding/ES后端库，通用Spring AI接口/HttpClient5不被误删。当前SHA-256（替代本记录前次构建hash）：

```text
rag-service             01c9c128e5b77357e435c2d51f1192fdfe6179814640e93b05a7bd505c550258
agent-service           b3e0a3a3556febc1ab6ed20d38f20d24e0e1fa80d458b687f68536ee46c9c3b9
listing-master-service  30bd7cffbd6af923f22f87a38e4b9f3e3a943f09d6f4e7fce7b03bc12f302794
memory-service          d6eb61f9c9732b543fff6ee1d661ff5ad69c611e1f41351f0928cb96fdc74e55
```

OpenSpec strict与diff whitespace通过。本轮没有模板配置改动、Docker/发布/真实SQL/backend操作、完整安全审计或状态机transition新增。ee4c9b9提交时任务统计为30/49；该历史计数在下文按原完整任务边界复核后纠正为32/49。实现任务不追加“先部署”条件，运行环境/profile/身份传输/旧调用证明/切流要求仍独立保留。

## 真实环境未执行与切流阻塞

1. 合规且经授权的 ES/embedding/重排真实目标、真实评分 profile/模型维度及权限尚未确认。现有私网 ES、host.docker.internal 不能豁免 URL 策略；不改地址、不公开本机服务、不向第三方发送语料。默认双路加重排真实验收未执行。
2. 旧 ES/Milvus 的 PK/schema、旧逻辑身份/时间表示、数据完整性及通用知识归属未在线盘点。模板只注册 listing.knowledge、memory.preference、memory.constraint；supervisor.knowledge 未注册/激活，未知数据不公开。
3. 三份 SQL 迁移已编写并有 H2 行为测试，但未对真实 MySQL 8.4 执行或重复执行。真实库/账号最小权限、备份/保留窗口由部署方核对；不据 H2 宣称 MySQL DDL 已验收。KI-37/38/39 的无关历史迁移缺陷仍未统一修复。
4. checked-in 配置、地址变量及健康名称静态核对不等于实际 Nacos namespace/group/data ID 生效、端口可用、镜像部署或真实 Dubbo 发现。密钥/角色/评分 profile 留空，worker/dispatcher 默认禁用；基础 readiness 是认证配置、控制表可查询和 RPC 已导出，能力证据须受权 inspect/provision 获取且保持新鲜，不在启动/健康主动探测后端。
5. 当前 A2A SDK 0.2.5.Beta2 无已验证专用凭据 transport 注入路径（KI-59）。REST/Dubbo 和进程内租约已验证；真实跨 A2A 房源 Agent 工具链仍失败关闭且未验收，不向模型 metadata 塞 token、不暗修 SDK。
6. 真实 backfill/删除水位、停写及旧 writer/在途 RPC 排空、消费者切流、RAG 停机的实际可用性、兼容发布周期及按事件水位回滚未执行。全部索引/集合/卷和旧部署保留，未进行 Docker/Nacos/SQL 运维、delete/prune/down-v。
7. 目标镜像 JRE 的 java.naming/jdk.naming.dns 模块未实测；HTTP 守卫不保护 Milvus gRPC 或业务 Dubbo，不能扩大安全声明。

LR-31～39、KI-55～60、KI-75记录持续开放门禁；KI-61～74、KI-76～77只记录已确认源码/测试缺陷的修复范围。没有得到完整 Mimosa 深度安全扫描结论；本次未擅自发起完整审计。OpenSpec仅勾选实际完成部分，保持未归档。

## 任务完成边界复核

本次按原任务、design及四份spec对ee4c9b9完整相关源码/POM/应用/Nacos/Compose/测试作只读独立复核。只补勾7.2、8.1，进度从30/49变为32/49；未拆任务凑数量，未更换架构或实际发布。

### 7.2：首次消费者切流版本的来源执行清理已完成

- `agent-service/pom.xml`、`listing-master-service/pom.xml`、`memory-service/pom.xml`及对应应用/Nacos/Compose中，本次后端执行依赖和配置已迁出；rag独立环境不向消费者泄漏后端，chat与marketing独立ES保留。
- `agent-service/src/main/java/com/bkanent/agent/config/AgentServiceConfiguration.java:18–24`仅注册消费/业务配置；`AgentRagConsumerProperties.java:6–20`不含后端寻址或模型执行配置。`rag-service/pom.xml:25–59`集中后端依赖。
- 来源执行类、旧索引触发和重复bootstrap已清理；旧Milvus名称包装只调用RPC。前次真实依赖树、四可执行JAR隔离证据见本记录上文。
- 本项验收已准备首次切流版本的源码/配置清理，实际配置生效、RPC发现/镜像部署和切流仍由7.1/7.3/8.3验收；6.7条件成功代理和8.5兼容周期不被代替。

### 8.1：受权回填实现与交错回归已完成

- `listing-master-service/src/main/java/com/bkanent/listing/rag/ListingRagBackfillService.java:20–31`扫描真源与持久revision身份并集；`ListingRagOutbox.java:37–74`同锁事务重新读取完整当前投影并经相同outbox发布，覆盖物理删除身份。
- `memory-service/src/main/java/com/bkanent/memory/service/MemoryRagBackfillService.java:24–80`受权、有界单页、领域锁后当前快照和持久身份；同`MemoryRagProjectionService` writer lock及投影/outbox逻辑。
- 完整`ListingRagOutboxTest`和`MemoryRagLifecycleTest`覆盖回填等待writer提交、完整快照重读、更新/删除交错、跨页删除、持久tombstone与事务回滚，683项既有报告已通过。
- `rag-index-lifecycle/spec.md:69–77`要求未知通用知识隔离不激活。模板不注册supervisor.knowledge，agent默认禁用；这是按规格处理未知归属，不要求新建行业语料，也不宣称通用空间迁移完成。
- 本项是实现/回归验收，真实旧数据盘点/回填、APPLIED/实际物理投影水位和切流仍保持未完成。QUEUED及nextCursor=null不是双后端应用证据。

### 1.3及剩余17项：保留具体未完成门禁

listing权威grant与memory owner/scope路径已有实现；`ListingRagProperties.java:19–20`、`MemoryRagProperties.java:20–21`默认角色为空，真实active actor/grant、读取/管理角色基线未核对。不能拿测试ADMIN替代实际权限事实，因此1.3不勾选。A2A传输KI-59、旧ID-only证明入口KI-75、合规目标/模型评分/schema、实际SQL/Nacos/部署与切流周期也不被本次复核消除。

必要人工输入、范围决定及授权后由实施方执行的迁移/回填/水位/切流/回滚步骤见[解除阻塞说明](isolated-rag-unblocking-steps.md)。本次只变更文档与checkbox；重新核对既有Surefire报告为683项、0failures/errors/skipped，并运行OpenSpec strict、任务总数/链接/引用范围及diff检查，不声称重新执行Maven或真实后端测试。此前七个外部联调类仍被排除。本轮KI-70～74、KI-76～77修复记录关联实际实施commit ee4c9b9，其他历史条目不改。
