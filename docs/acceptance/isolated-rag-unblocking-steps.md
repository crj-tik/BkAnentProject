# 独立隔离 RAG：已授权执行与剩余真实门禁

- 变更：`extract-isolated-rag-service`；分支：`dev-fix`；本轮交接基线 HEAD：`42a55bf`。
- 本文件替代原“必须人工决定的阻塞”说明。最新用户授权和范围决定已明确，不再要求用户重复批准，也不把实施方可执行的工作交还用户。
- 证据分层：2026-10-14当前22模块串行clean test／package均通过，每轮848项／137份Surefire XML，0失败／错误／跳过；七个外部联调类仍显式排除。14个Boot JAR、两官方SDK源精确重建／其余9官方类不变／license、后端依赖隔离及Java17私有probe编译已证，源LF指纹4e5307ef818d1263896ced5f78ea872e69523e87903cc156bc1ee833d40f476d。先前定向结果覆盖重叠，不能相加；历史失败保留。七个独立release镜像在原实例保留前提下开始构建，配置读回／制品编译仍不是应用已加载或默认检索成功。
- 前序文档代理仅负责本文／迁移清单的限制为历史分工，不是主会实施限制。当前主会据完整构建／制品证明完成8.7，进度 **28/49**；6.7以全量零旧RPC/proof、provider死注入清除、正常四Agent方法/index/backfill/outbox/physical worker保留闭合；1.4已核对安全运维库存及配置。真实应用／身份／两路水位／回滚等剩余21项仍按完整证据逐项完成，不由配置发布或测试数量自动补勾。
- 已查阅 LR-31～39、KI-55～60、KI-75 等历史记录。其旧的无内网放行、SDK 待授权、旧 RPC 成功代理/兼容周期表述属于前序决策背景；本轮以下明确授权口径适用于本次验收，LR/KI/spec 的同步留给主会，本编辑不替它们改状态。

## 1. 已确定的执行范围与安全边界

### 环境与目的地

个人开发环境、本地及本项目容器已获准变更；仅操作该范围，不扩张到其他项目或共享公司基础设施。公司 embedding、rerank、LLM 是预定数据处理目的地，不再要求建立新的脱敏项目或重新批准这些目的地。此决定不等于模型、协议、维度、评分尺度或调用已经验证，也不允许泄漏凭据或任意增加第三方目的地。

备份位于 `D:/project/rag-dev-backups/42a55bf-20261013-init`。其中含 `runtime.env`，文档编辑者严禁读取或打印它，也不读取/打印 inspect、env 或 dump 的私密内容。这里只记录非秘密摘要与允许读取的验证 JSON，不把敏感导出提交仓库。

### 身份与 scope

- 房源：ADMIN 可读所有未删除房源，包含非 `ON_SALE`；BROKER 仅可读 `ON_SALE`；MANAGER 不默认准入。不能再把“所有用户必须逐房源 grant”写成这两类角色的默认准入基线；显式资源授权及最终真源复核仍须按现行领域 policy 验证。
- 个人记忆：所有读取、写入和回填均 owner-only，ADMIN 也不能越过 owner；不得用管理角色或 SERVICE writer 绕开个人归属。共享/系统约束仍按显式读取/管理权限处理，不因个人 owner-only 规则虚构 owner。
- 失效用户 token 无效；token/beacon 的传播不能固化已失效账号的读取权。实际活跃 principal、角色、有效期及失效反例仍是待执行门禁。
- 公共领域 scope 为 `rag.scope.v2`，增加 `businessWideRead`；房源 policy 为 `listing-v2`；凭据为 `rag.credential.v2`，兼容 token/beacon。以上是交接口径，不是已完成当前测试的声明。
- READ 最终只允许真实 USER；common signer 拒绝 SERVICE READ，领域删除不可达的 SERVICE public 分支，所有实际消费者无 fallback。以上源码修订交接仍待当前测试；SERVICE writer 的生命周期写权限不等于 READ 或用户管理权。
- 正常 REST、工具包装、业务 READ、生命周期/outbox 和 worker 对实际两后端的物理 hash reconcile 保留。
- KI-79实际缺口为个人记忆query、missing-session/final reauth门禁；现有service已保持owner immutable和`session_id` UNIQUE，**没有已证明的other-owner session takeover**。相关修复/反例待当前test成功，不能以早期覆盖推测替代确认事实。

### A2A 与旧入口清理

A2A最小真实SDK transport patch/auth beacon已授权，无须再请求SDK范围批准。仓库模块 `a2a-sdk-transport-patch`，版本 `0.2.5.Beta2-bk-transport1`：解包原binary，patch compile官方 **`A2AClient` + `JdkA2AHttpClient` 两个source**，不是单source；不修改`.m2`、不反射、不另建第三套JSON-RPC客户端。22模块clean compile已通过，真实SDK调用与transport/auth生命周期仍待当前test/运行验证。

Explore全量复核已确认无消费者旧 `AgentRpcService.syncListingKnowledge(Long)` / `deleteListingKnowledge(Long)`、signed listing legacy reconcile/proof及collector删除；其他Agent四方法和正常index/backfill/outbox/physical worker、REST/工具/业务READ保留，营销独立ES不删。不再建设旧ID-only条件成功代理、不等待旧兼容发布周期。清理结论是源码静态证据，不再列是否已删除为阻塞；当前test首轮失败待修、正常入口回归和真实生命周期/物理水位尚未通过。

### HTTP 出站策略

仅使用 `mode` / `allowed-hosts` / `internal-networks` 受管配置，不新增豁免 DSL：

- `PUBLIC_ONLY` 为默认；公司精确公开 host 为 `open-chatgpt.ke.com`，不是整个公司域或通配符。
- `MANAGED_INTERNAL` 只允许精确 host 对应配置的 RFC1918/ULA 网段。项目 Docker 网段为 `172.24.0.0/16`。
- `host.docker.internal` 在容器中对应 `192.168.65.254/32`，Windows 宿主对应 `10.117.102.54/32`；两种执行位置使用不同映射，不把两个地址或整段私网无条件放行。
- 仍校验全部原始 DNS A/AAAA，实际地址绑定、每次新连接/重连复核和 TLS host 校验；禁止代理与重定向，混合非法 DNS 答案失败关闭。模式授权不削弱这些门禁。
- 该 HTTP 策略不覆盖 Milvus gRPC 或业务 Dubbo，不能据 HTTP 测试扩张安全声明。

`supervisor.knowledge` 继续未注册/未激活，未知语料不公开，不新建行业语料。营销独立 ES 搜索不在本次提取范围。

## 2. 已取得的实际运行证据

以下区分Docker/主会交接与已读取safe JSON；本编辑未执行运行命令、读取原始私密日志/配置/备份payload或独立重算checksum。safe JSON精确路径见本节，不猜秘密文件路径。

| 阶段 | 已取得证据 | 不可扩大为 |
|---|---|---|
| 原环境备份 | MySQL `8.4.11` 四库受限 dump，`168764` bytes；Nacos 原十二份 config 导出，`BK-AGENT` 与 `public` 各六份，原均无 rag 配置 | SQL payload 内容已公开、所有索引/集合已备份或恢复已演练 |
| Nacos运行快照 | docker commit pause snapshot + docker save，archive `293634560` bytes，有archive checksum；最新隔离Derby crash recovery/十二原config hash匹配已证 | cold Derby备份或应用rollback；它是paused crash-consistent快照恢复 |
| 原容器保留 | Nacos `978571137a06`、MySQL `325cc757feb7` 未重建；根 `.env` 未改 | 新应用镜像已部署 |
| 升级首次失败 | checksum 因 CRLF 未归一失败，在 DDL 前拒绝 | 已迁移成功；失败记录必须保留 |
| 升级修正 | LF 修正后六步实际运行两次，均 Verified；原业务计数不变 | 应用/RAG 检索验收 |
| runtime 最小权限 | `rag_runtime` 只对两张 `bk_rag` 控制表具 SELECT/INSERT/UPDATE，无 DDL；跨 `bk_auth` SELECT 实测拒绝 `1142` | 所有应用权限与业务 scope 已验证 |
| 独立 fresh | Compose 项目 `bk-anent-dev-initcheck`，新空卷 `bk-anent-dev-initcheck_fresh-data`；首启 healthy 九步；fresh 再跑两次各九步，upgrade 再六步均 check | 原卷被重建、数据回填或真实模型调用 |

版本初始化 manifest 为 `v20261013.1`：fresh 九步、upgrade 六步；固定 DB/SQL 映射、LF SHA-256、shared runner 加 check。升级保留原计数：listing 0、preference 0、constraints 17、shared memory 3、users 2、roles 3、workflows 3、checkpoints 46。

允许读取的 `D:/project/rag-dev-backups/42a55bf-20261013-init/fresh-init-verification.json` 已读取，记录 fresh 项目/容器/保留卷、manifest、healthy、`fresh/fresh/upgrade` replay 和 `counts_unchanged=true`；计数为 auth users 2/roles 3、contract template 1、settlement rules 2/tier 1、memory lock 1，业务语料和 RAG 两控制表行数均 0。history facts 按 auth/interview/agent/rag/listing/memory 为 1/2/3/1/1/1，合计 9。JSON 未附各步 SQL 原始输出或 checksum 值，不把交接摘要说成本编辑独立重算的证据。

最终Nacos证据 `D:/project/rag-dev-backups/42a55bf-20261013-init/nacos-publication-verification.json` 已读取：namespace ID `BK-AGENT`、group `DEFAULT_GROUP`，选中 `auth-service.yaml`、`rag-service.yaml`、`listing-master-service.yaml`、`memory-service.yaml`、`agent-service.yaml`、`customer-service.yaml`、`marketing-content-service.yaml`；authenticated_login/readback_equal均true、a2a_domains=11，逐data ID SHA-256保存在该安全JSON。**七最终配置认证publish/readback及11域A2A信任一致已通过，不再列扩展版待再次读回或缺发布权限为阻塞。**Nacos原容器未重建，应用实际加载/生效仍待验证。只七data ID，不整目录覆盖。

保留历史观察：`bkagent`登录HTTP200、顶层accessToken未回显；rag早期POST true立即GET404为异步可见性，用限定deadline readback retry最终核对，不以上传响应替代读回。后续配置如再变更需相同逐项读回，不因此将当前最终版本降为未通过。

A2A env保持 `{DOMAIN}_A2A_KEY_ID` / `{DOMAIN}_A2A_SIGNING_SECRET`；auth response保持 `AUTH_RAG_RPC_RESPONSE_KEY_ID` / `AUTH_RAG_RPC_RESPONSE_SECRET`，不改用新的AUTH前缀。其他六app YAML范围扩展保留九域真实guard能力、不是全拒绝；六服务尚未部署，配置读回不等于运行生效。

### 旧后端、安全恢复与脚本门禁新增实证

已读取 `D:/project/rag-dev-backups/42a55bf-20261013-init/backend-inventory-verification.json`。Docker交接四旧后端先停，卷只读备份后恢复原容器healthy；ES实测green/total_documents0，`listing_info`与`marketing_content`均count0/mapping `{}`，旧UUID分别`SB7OICsnTEi5TiYYxIFzYA`与`8XOtbKnuRRmXRQhr2R4GmA`保存在证据，不当新schema。Milvus REST default集合`[]`；etcd固定catalog key `by-dev/meta/root-coord/database/db-info/1`有1库/0集合/0partition，实体0/identity hash清单空，旧PK/model/hash absent或不适用。旧空catalog已盘点，不再要求用户提供旧映射/批准盘点，不能补造旧语料；新schema/provision/模型与默认检索仍须验证。营销独立ES保留。

已读取 `D:/project/rag-dev-backups/42a55bf-20261013-init/mysql-restore-verification.json`：隔离容器 `bk-anent-dev-restorecheck-mysql-1`、独立卷 `bk-anent-dev-restorecheck_restore-data`，isolated/restored/original_counts_match=true；listing0/preference0/active_constraint17/shared_memory3/auth_users2/auth_roles3/async_workflows3/checkpoints46与原计数匹配，不读取SQL payload。

已读取 `D:/project/rag-dev-backups/42a55bf-20261013-init/nacos-restore-verification.json`：隔离internal实例 `bk-anent-dev-nacos-restorecheck-internal` / network `bk-anent-dev-nacos-restorecheck_isolated` ready，有效published ports=[]、mounts=[]，Derby crash recovery verified；十二原config（BK-AGENT/public各六）hash读回匹配，original_instance_untouched=true、cold_consistent_backup=false。**MySQL隔离restore和Nacos paused snapshot crash recovery已证，但不是cold备份恢复或8.8事件/config/应用rollback**。不因继承旧HostConfig绑定误判有效宿主端口，原Nacos未改；继续保留真实应用回滚门禁。

已读取 `D:/project/rag-dev-backups/42a55bf-20261013-init/script-gate-verification.json`：bash语法/LF/compose配置/fresh-upgrade checksum dryruns=true。真实status仅mysql+nacos返回0；missing rag=1、with-rag不能省rag=1、stopped auth=1；wrong host=124、live localhost MySQL=0。这证明选中组件和失败关闭/有界超时场景，不是当前app/RPC/backend或默认双路能力验证。原容器healthy也不等于新rag/app已部署。

### 最终库存/配置和模型旁路预检（已读safe JSON）

- `D:/project/rag-dev-backups/42a55bf-20261013-init/operations-inventory-safe.json`：当前data ID总数 **BK-AGENT17/public21/bk-acceptance-smoke5**，原16/21/5；选中十二原config备份不是Nacos总数。旧auth image本地存在但容器exited；旧listing/memory/agent容器exited，共同image ID `sha256:a6e760985207af69747d3306bac1e714bfeab829ff6d6b21c90cfcfb6c38264d`本地缺失且repo_digests空，不能拿该ID或可变dev标签当可重建rollback镜像。库存仅记录原实例/镜像/端口/卷及archive非秘密checksum元数据，未证明新app启动或rag image创建；不扩大旧secret权限，不读containers-before/full.env/raw dump。
- `D:/project/rag-dev-backups/42a55bf-20261013-init/configuration-validation.json`：严格PyYAML+显式duplicate检测，16app/9owned YAML逐文件hash，compose/empty example失败关闭、bash/LF、publisher AST、manifest checksum/diff通过；application_build_or_start=false，不作为部署/真实guard能力证据。
- `D:/project/rag-dev-backups/42a55bf-20261013-init/model-preflight-verification.json`：仅synthetic text，through_application_guard=false。embedding旁路 `https://open-chatgpt.ke.com/v1/embeddings` HTTP成功，configured/response model均 `baai-m3-1b-base-v1-embedding-20240619`、dimension1024、all_finite=true，不代表当前应用guard/新schema/default双路通过。配置rerank目标 `http://host.docker.internal:18119/rerank` 返回HTTP401，http_success=false、profile_not_activated=true；profile维持关闭，保留失败，不猜scale/scoringVersion或降级称默认通过。后续由实施方用获准凭据来源排查认证，不回显或扩大旧secret权限。

主会以安全库存/配置/脚本和Nacos/model证据闭合1.4，进度27/49；不把这些局部证据扩张为完整test、应用生效、默认双路/rerank或rollback通过。

## 3. 实施方继续执行的顺序

以下工作已处于授权范围内，不要求用户再批准或亲自执行。未完成的门禁不是新的授权阻塞；每步只以该步真实证据关闭。

### A. 当前构建／制品已通过，后续源码改动重验

Explore全量已确认旧RPC/proof/collector清理以及正常index/backfill/outbox/physical worker/其他Agent四方法和营销独立ES保留。当前22模块clean test及package各848项、0失败／错误／跳过，14Boot JAR／SDK补丁／依赖隔离／Java17私有probe编译通过，8.7已完成；精确日志与源指纹见本地验收记录。早期compile、撤销event空message、redirect、memory/customer、H2 schema及真实stream夹具失败全部保留，不沿用历史683项／旧JARhash。当前本地回归不能代替真实Auth／SDK／模型／容器验收；后续执行源码或构建配置变化须重跑对应测试及package/hash，不将新产物挂旧验证标签。

当前反例至少覆盖 ADMIN 非 ON_SALE 未删除房源正向、BROKER 非 ON_SALE 拒绝、MANAGER 默认拒绝、个人记忆跨 owner 的所有读/写/回填（含 ADMIN）拒绝、失效用户 token/beacon 拒绝。A2A 使用真实 SDK transport，检查同步/异步/查询/恢复及重新认证、到期/重启/撤销的凭据生命周期；禁止 token 落模型参数、普通 metadata、RunnableConfig、日志、outbox 或 checkpoint。直接 Dubbo 单测不能代替真实 A2A。

HTTP 覆盖 PUBLIC_ONLY 与 MANAGED_INTERNAL 的精确 host/网段匹配、容器/宿主不同 host 映射、raw DNS 特殊/混合答案、重连地址绑定、TLS、proxy/redirect deny。stub 成功不代替目标实际能力。

### B. 限定 Nacos 发布、读回与应用部署

七最终限定data ID/11域A2A信任的认证发布/readback SHA-256已一致，当前不重复列为待发布。继续核对部署后运行应用实际加载及生效值，不输出密钥。后续如变更版本，保存旧配置并逐项按限定deadline重试读回，超时明确失败，不以POST true或瞬时GET404定最终结论；不整目录发布。

依据当前已验证产物构建不可变 images，限定本项目受影响服务部署；记录 image digest、端口/网络、配置版本、JRE `java.naming` / `jdk.naming.dns` 及真实 auth/RAG/listing/memory RPC provider/consumer 发现和验签。消费者的基础业务 readiness 不以 RAG backend 作为全局硬依赖。容器 running、TCP 或基础 readiness UP 不等于检索能力通过。

初始化只补当前确实缺失的 check/应用访问证据，不重复要求批准已完成的六步 upgrade 或九步 fresh。SQL 验收工具外部值使用参数绑定，库/表/字段标识固定；runtime 不取得 DDL 权限。

### C. 模型/backend/逐空间身份核验与显式 provision

历史embedding旁路已测公司目标model一致、1024finite，历史rerank旧目标401／profile未启用均保留；不覆盖当前新的governed端点／caller key／精确身份和profile配置。当前模板与14live dataID一致、memory精确版本接线回归通过，真实应用仍需核对ES／Spring AI embedding／Milvus及默认双路／governed响应的alias、revision、artifactDigest、scoreSemantics、scoringVersion。实际响应漂移或认证失败须保持非零，不凭名称猜LOGIT／放宽版本白名单；旁路或模板成功不证明应用guard与默认能力。

按[逐空间清单](isolated-rag-migration-inventory.md)使用已证旧ES两空索引/mapping `{}`/UUID和Milvus精确空catalog：本次旧目标无实体，旧PK/model/hash为absent，不再等待盘点权限或补造旧映射/语料。SQL仍有17约束和3共享记忆，不因旧索引空而免除当前真源owner/read policy、身份、时间表示、完整新投影/删除和应用水位验证。历史未知复合键不按下划线猜owner，不删除旧记录；supervisor不provision、不回填。

使用已存在的受权管理 RPC inspect/provision/replay 和索引 status，不增加公网管理面，不让用户在模型工具 JSON 中构造认证。只显式 provision 新空间，schema/维度/身份冲突停止，不覆盖旧集合；短期能力证据过期须重新受权获取。

### D. owner-aware 回填、两路物理水位与默认检索

房源回填保留领域有界分页、锁后当前完整投影、同事务 outbox 和持久删除身份；记忆回填包含 owner-only 个人范围和独立获准约束范围，不能将历史全 owner 扫描接口语义当新授权。当前分页/receipt 接口由 freeze 后实现与测试核实，不用本编辑猜新接口。

`QUEUED`、`ACCEPTED`、nextCursor=null 只证明排队/接收/本轮扫描结束，不证明 APPLIED。启用 worker/dispatcher 前核对目标和权限；逐项核对最新 revision/hash/schema/tombstone、两路 appliedRevision、实际物理投影及删除结果。worker 物理 hash reconcile 必须保留，不能和 signed listing legacy reconcile 一同清理。合法 STALE 要能证明覆盖它的最新版本已应用；缺交付事件不由 replay 凭空补造。

实际验证默认 ES+Milvus+融合+rerank、两路硬过滤等价、领域最终排序/真源复核、跨空间/跨 owner/服务越权、更新/撤权/删除/迟到写/partial 补偿。公司目的地授权不替代这些验证；单路、关闭 rerank 或 stub 不是默认路径验收。

### E. 停机、切流与回滚

在个人开发环境/本项目范围由实施方执行停写点记录、旧实际 writer/实例/在途调用排空、增量与删除水位追平和消费者切流；无消费者的两旧 RPC 及 signed legacy reconcile 不再设置兼容周期。仍保留正常 REST/工具/业务 READ、生命周期及必要回滚数据，不将接口删除误作运行水位已追平。

演练 RAG 不可用时房源详情、结构化记忆和无知识 Supervisor 可用；知识开关关闭零 RAG 调用，检索明确失败，显式降级带标记，不恢复旧本地执行 fallback。

回滚记录配置/镜像及事件水位差异；暂停相关写入，保留 outbox、bk_rag 和 tombstone，按原 eventId/revision 恢复领域交付，再核对真源、账本及实际两路投影。replay 只处理已接受的合法事件，不能补造语料。恢复写入前验证删除/授权/默认能力，回旧执行路径仍须满足现行HTTP策略。MySQL隔离restore与Nacos paused snapshot隔离crash recovery已证，但不等于8.8事件/config/应用rollback或cold备份；不删除旧index/collection/volume，不prune或down -v。

## 4. 2026-10-14 当前执行证据与剩余门禁

当前限定重新发布rag/memory，两份备份后认证LF读回一致，14精确dataID与当前模板全一致。七服务release env只含必要变量并使用当前根同源identity signing，历史环境未混用；memory明确注册当前governed评分版本。实际MySQL `rag_runtime`账户不存在，不是已证密码错误；独立release账户只获两表SELECT/INSERT/UPDATE，项目host登录/两表读取通过、跨auth1142。

五旧app均缺相同containerd blob，逐源commit失败、无可加载save归档；保留所有旧停止容器，不以inspect备份宣称完整应用回滚。当前只独立新release实例和固定Java17 runtime的已验JAR镜像，旧实例不重建。原Redis／namesrv成功；broker store卷根0:0／0755不可写已证，实际仅根chown3000:3000后同原id／mount／policy启动，TCP10911及mqadmin clusterList exit0、broker-a注册。未递归改权限或删数据；日志残留store_failed分类待核对，不宣称全无错误。七新镜像首auth构建RUN失败exit1仍在细分，尚无新app启动；不移除JAR/hash/Java模块门禁。新版能力及agent总线仍按真实路径验证，详细safe证据见本地验收记录。

### 历史冻结时点（保留原观察，非当前状态）

已取得22模块clean compile成功、Explore源码清理、初始化/最小权限、最终七Nacos配置11域trust认证发布读回一致、旧ES/Milvus空catalog、MySQL隔离restore及Nacos隔离Derby crash recovery证据。首轮及fixed轮fixture失败/下游skipped已保留，主会final reactor test正在运行、尚无结果；当前完整tests/package成功、新JAR/image hash、images/deploy、应用实际生效、经应用guard的模型请求/新backend schema/default双路/rerank、回填/APPLIED/物理水位、outage、切流及8.8事件/config/应用rollback仍无成功证据。embedding旁路1024finite只属预检，rerank401/profile关闭和旧app missing imageID/非可重建rollback均保留为实证限制。隔离备份恢复不等于应用回滚，Nacospaused crash recovery不称cold备份。主会已闭1.4为27/49；本文不自行补勾1.2、不关闭8.8，不扩大为完整运行验收或深度安全审计。

历史本地测试、失败原始记录与本轮状态分别见[本地验收记录](isolated-rag-local-verification.md)。任务计数由主会在源码/运行证据到位后更新；重开任务不是退步，也不以文档改写补勾完成。
