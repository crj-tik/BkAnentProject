# 独立隔离 RAG：必须人工决定的阻塞与解除步骤

- 变更：`extract-isolated-rag-service`；分支：`dev-fix`。
- 实现核对基线：`ee4c9b9`。本文件是解除阻塞操作说明，不是部署、数据迁移、切流或完整安全审计的完成证明。
- 权威约束：[design](../../openspec/changes/extract-isolated-rag-service/design.md)、[任务清单](../../openspec/changes/extract-isolated-rag-service/tasks.md)、LR-31～39、KI-55～60、KI-75。原任务完成边界复核后为32/49，补勾7.2、8.1；其余17项保持未完成，不以本文件替代运行验收。
- 本轮不执行真实 SQL、Nacos 发布、Docker 更新、后端请求、回填、停写或删除；现有索引、集合、卷与部署均保留。

## 1. 先由负责人确认的最小输入

以下不是要求负责人亲自运行所有命令。负责人只需作出涉及授权、数据归属及范围的决定；工具能完成的盘点和操作，在对应授权后由实施方执行。秘密值只能通过环境变量或秘密管理注入，不放回复、文档、提交、模型参数或日志。

### 第一步：指定目标和分阶段操作授权

提供目标环境名称、主机/集群及 Compose 项目归属、负责人，以及实际 MySQL、Nacos、Dubbo 的连接配置来源。Nacos 必须明确 namespace **ID**、group、各受影响服务 data ID；显示名称不能代替 ID。这些事实可以授权实施方从目标读取，不必人工猜测。

授权按阶段列出，不能只给一个“继续 apply”：

1. 只读运行盘点和获准数据抽样。
2. 配置/镜像/数据库/索引快照备份及存储位置、访问权限、保留期。
3. 三份指定 SQL 迁移、限定 data ID 发布及限定服务构建/部署。
4. 新空间显式 provision、授权种子维护、回填及事件 replay。
5. 停写、旧实例/在途调用排空、消费者切流、停机反例及水位回滚演练。

每阶段只操作批准的目标；备份或导出也可能包含敏感语料，需要指定安全存储。无需批准删除旧数据，本变更不执行删除索引/集合/卷、prune 或 `down -v`。

**通过条件**：目标资源、操作范围、备份位置及恢复批准者明确；否则不能进入相应变更阶段。关联任务 1.2、1.4、5.3、7.1、7.3、8.2～8.4、8.8。

### 第二步：批准合规后端及语料目的地

为 ES HTTP、Spring AI embedding HTTP 和重排 HTTP 提供已获准的实际目标及凭据注入来源。所有目标仅允许 http/https，全部 DNS A/AAAA 必须满足现有地址策略，不得使用 localhost、环回、私有、保留或映射地址。现有私网 ES 与 `host.docker.internal` 重排不能直接沿用，也不能由实施方自行公开或替换。

另外明确新 Milvus 的 host/port、database/collection、凭据注入及语料存储授权。HTTP 地址守卫不覆盖 Milvus gRPC，不将其强行解释为 http/https 目标，也不据此豁免任何实际 HTTP 分支。

同时确认：

- 哪些房源/偏好/约束字段允许发送给 embedding、重排提供方，谁批准该数据处理目的地。
- 实际 embedding provider/model/version/维度与旧集合事实；模板 1024 维不是测量结果。
- 实际重排 native 或 DashScope 协议、provider/model、原始评分尺度、profile/scoringVersion 及对应验证证据。LOGIT 或 BOUNDED_0_1 不能凭名字猜测；memory 允许版本须与实际注册一致。
- 旧索引/集合的只读盘点权限：PK、schema、metadata、文档逻辑身份、时间表示、模型、数量/hash、授权和删除身份。偏好旧下划线 ID 不通过字符串拆分猜 owner。

**通过条件**：目标经过授权且符合 URL 策略，实际模型/评分证据可验证，旧数据能够按真源核对。缺少这些输入时不能完成默认 ES+Milvus+重排真实验收；单路、关闭重排或 stub 都不是替代品。关联 KI-57/58/60，任务 1.2、4.1、4.2、6.3、8.2。

### 第三步：确认最小权限基线及未知通用知识处置

指定实际活跃用户/角色及经批准的读取/管理范围：

- listing 的资源授权通过受管 grant 入口维护，不推断 brokerId 等于认证 userId，不向所有活跃账号自动授权。
- 偏好 owner 始终是已解析真实用户；约束读取和管理角色须显式配置。
- 当前 memory 管理还要求角色属于 ADMIN/MANAGER 且在 `memory.rag.constraint-admin-roles` 中，不能只配置任意角色名就认定可管理，也不自动给用户授予 ADMIN。
- RAG 管理同时要求 USER、真实 signed principal、服务/空间操作许可与 adminRoles。SERVICE writer 不能通过管理授权或变为搜索用户。
- 通用知识归属未确定时，继续保持 `supervisor.knowledge` 未注册/未激活，未知语料不公开。这不要求建设新行业语料；若要迁移旧通用知识，须批准受管导出及逐项归属整理。

服务 HMAC、auth 响应、领域 scope 等独立秘密只经环境/秘密管理部署，签名绑定不能用普通 userId、gateway 标记或 Dubbo 服务名代替。

**通过条件**：权限批准记录与实际活跃 principal 一致，默认空角色/无 grant 保持拒绝；获准正向及跨用户/跨域/服务越权反例通过。权限配置的实际生效由实施方核验，负责人无需自行写 SQL 种子。关联 KI-55；任务 1.3 的权威路径与运行权限种子须分别验收。

### 第四步：批准两处不能靠环境参数解决的实现范围

这两项不是部署操作，也不是要求负责人手工修代码：

1. **A2A 专用凭据传输（KI-59，任务 3.5、6.2）**。现用 SDK `0.2.5.Beta2` 没有已验证的 per-task 专用 header/transport 注入路径。需要批准针对该路径的最小 SDK 升级、修补或受支持 transport 变更范围，并允许先核验 API 再确定具体技术版本。批准范围不等于某版本已经可用。获准后实施方检查受支持接口、完成同步/异步/查询/恢复的生产调用接线及测试；未完成前保留失败关闭，不能把 token 放模型参数、metadata、RunnableConfig 或 checkpoint。
2. **旧 ID-only RPC 的可信证明入口（KI-75，任务 6.7）**。旧 `syncListingKnowledge(Long)` / `deleteListingKnowledge(Long)` 没有认证、expected revision/hash 或排空证明。负责人需确认获准的遗留调用证明来源及接入范围；证明必须来自可审计的提交/删除及次序事实，与真实 stop-write/drain 记录对应。实施方据此实现最小条件成功代理。独立 signed reconcile 契约已经存在，但 agent 查询当前状态后自签名、配置 boolean 或仅给 ID 都不能补造证明。若决定不支持旧成功路径，必须先明确变更兼容要求，不能自行勾选 6.7。

**通过条件**：有明确范围授权和可实现的受支持传输/可信证明来源，并通过真实生产路径测试。普通“部署到测试环境”授权不能自动解除这两项范围阻塞。

### 第五步：指定停写与兼容发布窗口

指定受影响房源/记忆写入口、停写窗口、旧 writer/实例及在途调用的排空标准、兼容周期的起止发布、恢复批准者和可接受失败处理。memory bootstrap（模板默认启用）、衰减等后台写任务也纳入维护控制；停用 dispatcher/worker 不代表领域没有写入，相关开关处置须批准。至少一个兼容发布周期是运行事实，不能在一次本地提交中假报已经经过。

**通过条件**：安全、身份、数据、默认能力全部通过后才允许恢复写入；旧 RPC 签名/引用的删除需消费者全部升级、旧调用清空及水位追平后的单独清理发布。关联任务 8.3、8.5、8.8。

## 2. 获准后实施方执行的顺序和验收证据

各步骤仅在第 1 节对应输入和授权齐备后执行。每一步保留可复核结果；失败不越过门禁，也不要求用户重新亲自完成实施。

### A. 只读盘点与备份

核对实际 Git 版本、运行镜像 digest、端口、Compose 项目、JRE `java.naming` / `jdk.naming.dns` 模块、Nacos namespace/group/data ID 与应用生效值、Dubbo 导出/发现及两控制表。对每个知识空间补充旧 schema/模型/身份/时间/授权/删除事实，填写 [逐空间清单](isolated-rag-migration-inventory.md)。不得将源码名称或基础 readiness 当在线盘点。

按批准范围备份生效配置、镜像、空间映射、三库及相关索引/集合快照，记录最新领域 revision/hash/tombstone、outbox 与 RAG 两路应用水位。核查备份完整性和恢复路径；导出的敏感 payload 不提交仓库。

### B. 迁移、最小权限和限定配置发布

先核对以下固定库和迁移内容，再对批准目标逐文件执行并重复执行核验：

- `sql/migrations/20261012_rag_control.sql` → `bk_rag`。
- `sql/migrations/20261012_listing_rag.sql` → `bk_listing`。
- `sql/migrations/20261012_memory_rag.sql` → `bk_memory`。

现有 Compose 首启挂载不包含三份迁移，新卷也不能跳过检查。真实 MySQL 8.4 DDL、重复执行与 runtime 账号最小权限需要真实验证，H2 通过不等同于完成 5.3。盘点、抽样、对账及验收工具中所有外部 SQL 值（含 userId/spaceId/documentId/eventId/cursor）都使用 prepared statement/JDBC 参数绑定，不拼接、format 或 f-string 生成 SQL；库/表/字段标识符固定或来自严格受管集合。RAG runtime 对两控制表只需 SELECT/INSERT/UPDATE；DDL 管理账号单独使用。payload 的批准保留期必须覆盖迁移/重放窗口，SQL 注释基线为至少90天；最新投影、revision、身份/hash 和 tombstone 控制事实保留，不因90天到期自动删除。历史payload清理需另行批准，本次不执行。

现有迁移脚本支持的**只列文件示例**如下，它们不执行 SQL，也不验证数据库：

```bash
bash scripts/deploy/apply-migrations.sh --file sql/migrations/20261012_rag_control.sql --dry-run
bash scripts/deploy/apply-migrations.sh --file sql/migrations/20261012_listing_rag.sql --dry-run
bash scripts/deploy/apply-migrations.sh --file sql/migrations/20261012_memory_rag.sql --dry-run
```

真实执行支持 `--file` / `--yes`，但当前脚本固定从根 `.env` 读取 root 密码，不支持把外部密码环境变量视为已接线替代方式。本说明不读取该文件。若批准只用秘密管理或专用迁移账号，应由实施方使用获准客户端/工具，必要工具变更先明确范围，不通过输出密码或改权限绕过。`--file` 用仓库相对路径或部署端 POSIX 绝对路径，不将 Windows `D:/...` 当脚本已支持的绝对分支。

发布仅限核对后的受影响 data ID。现有 `config-init` 使用 namespace tenant、固定 DEFAULT_GROUP、显式空格分隔 data IDs，POST 会覆盖所选配置；未接入 Nacos 登录认证参数。不能把外部 `NACOS_PASSWORD` 自动认定为该 command 支持，也不能整目录覆盖。使用获准的认证发布方式，发布后读回并核对**应用生效值**，不只看上传成功。默认空 roles/profile/secret、禁用 worker/dispatcher 不由实施方擅自启用。

### C. 定向部署与 RPC 发现

沿本项目 Dockerfile/module 规则构建独立 `bk-anent-rag`，使用对应已验证版本而不把可变 `:dev` 当备份。模板为 `MAVEN_MODULES=rag-service`、HTTP 9018 loopback、Dubbo 20898 不向宿主发布、QoS关闭；先核对目标冲突和访问方式。验证 auth、RAG、listing、memory 的真实 RPC provider/consumer 及签名接线，消费者不把 RAG 后端列入全局硬就绪条件。

`start.sh` 只支持 `minimal|full|mcp` 加 `--with-rag`，没有服务选择参数，会启动档位全量且可能触发配置发布；不能当定向 RAG 更新命令。由专门容器实施按批准服务重建，保留原配置及可回滚镜像。`status.sh --with-rag` 可作辅助，但 `ps`、TCP、running、无 curl 时成功退出或基础 readiness UP 都不能替代预期实例、RPC及实际能力核验。

### D. 受权管理、回填与应用水位

没有额外公网 RAG 管理 HTTP 或现成 `rag-admin` CLI。采用现有 Dubbo `RagSpaceAdminRpcService.inspect/provision/replay(request, authentication, credentials)` 和 `RagIndexRpcService.getStatus`；若需要专用短期验收客户端，由实施方在获准范围实现，不让用户在模型工具 JSON 中构造认证。

1. 使用真实 USER/adminRoles 和服务/空间允许操作做 inspect；发现 schema/dimension/权限不符则停止。新空间只通过显式 provision 创建，不搜索时自动创建，不覆盖旧集合。管理证据最长60秒，实际能力核验时重新受权获取。
2. 用 `PUT /listings/rag/grants/{actorUserId}/{listingId}?active={bool}` 维护批准 grant，使权限变更进入同事务 projection/outbox。不是直接 SQL 插种子；需要实际 access token 和受管角色。
3. 房源：`POST /listings/rag/backfill?afterId=0&limit=100`，最大200。返回 QUEUED 事件列表，没有 nextCursor。按最后返回的合法 `listing:{numericId}` 推进 afterId，直到返回空列表；扫描源与持久 revision 身份并集，包含物理删除身份。记录每页事件及水位，不把列表返回当双索引完成。
4. 记忆：`POST /internal/memory/rag/backfill`，省略 cursor 为 `p:0`，limit 默认100/最大500。按响应 nextCursor 原样续页，包括空 receipts 阶段；nextCursor=null 只结束本轮扫描，不是跨页原子快照或 APPLIED 水位。收集 receipts，持久身份和扫描期间增量另行核对。
5. 只有目标与权限校验通过后，按批准配置启用 dispatcher/worker。对最新文档逐项核对 revision/hash/schema/tombstone 与两路 appliedRevision、APPLIED 状态，并核对实际两后端投影的 revision/hash/schema 和删除结果，不能仅用账本状态代替物理核对。发现 RETRY_PENDING/CONFLICT/EXHAUSTED 或缺口先处理。旧事件合法 STALE 时必须证明覆盖它的最新版本已应用，不要求每个历史事件都 APPLIED。submit/replay 返回 ACCEPTED 仅代表持久化接收，不等于完成；replay 只处理 RAG 已接受的最新合法事件，未成功提交或耗尽的领域outbox另按原eventId/revision核对并重试，不能用replay补造丢失语料。

### E. 真实默认检索与反例

用获准语料和实际身份验证：默认 ES+Milvus+融合+重排、schema/维度、评分 profile、两路硬过滤等价、跨空间/跨用户/服务越权、更新/删除/迟到写及 partial 补偿、领域最终排序、旧 REST/技能/历史审计。重排提供方只收到已授权的候选。

通过已修好的实际 A2A 专用传输调用房源 Agent 工具，不能用直接 Dubbo 单测代替 6.2。关闭生产知识开关必须零次 RAG 调用。演练 RAG 不可用时房源详情、结构化记忆和无知识 Supervisor 可用；检索返回明确错误，显式降级保留标记，不能恢复旧本地执行 fallback。

**通过条件**：真实默认路径及所有不可降级安全/身份门禁通过，数据数量/身份/hash/删除缺口可解释；记录命令、版本、目标、时间和结果，不保留秘密或未批准正文。

### F. 停写、排空、切流及回滚

按批准窗口暂停受影响写入口，记录停写点；核实旧 writer/实例和在途 RPC 均排空，增量及删除水位追平。先完成 KI-75 的可信旧调用证明路径及反例，不拿配置开关当排空证据。保存切流前配置/镜像/数据水位，部署已清理来源执行的消费者版本，全部安全、身份、数据、默认能力门禁通过后才恢复写入。

优先恢复兼容新契约版本：暂停写入、保留全部 outbox/bk_rag/tombstone，记录差异水位；领域未成功提交或耗尽事件按原eventId/revision核对并恢复交付，RAG已接受的最新合法事件才走replay。对真源缺口重新受权构建完整投影，不能由replay生成缺失历史语料；核对账本、实际两路投影和真源后恢复。若必须回旧执行版本，先重放窗口差异并验证删除/授权，且旧 HTTP 路径也必须满足当前地址策略；不合规则保持检索不可用，不仅改路由，也不删除任何数据。

完成至少一个实际兼容发布周期、确认全消费者升级与旧调用清空后，另行实施旧 RPC 签名/引用清理。期间保留获准纯 REST/工具包装和回滚所需数据。

## 3. 可复核入口与完成边界

- 迁移脚本参数/限制：`scripts/deploy/apply-migrations.sh:10–13,38–66`；全档位启动：`scripts/deploy/start.sh:24–57`；辅助状态限制：`scripts/deploy/status.sh:34–75`。
- 配置发布限制：`docker-compose.yml:166–205`；RAG部署模板：`docker-compose.yml:619–677`。
- 管理角色交集：`memory-service/src/main/java/com/bkanent/memory/service/MemoryRagSecurity.java:59–60`。
- 房源入口：`listing-master-service/src/main/java/com/bkanent/listing/controller/ListingRagController.java:20–42`；回填：`listing-master-service/src/main/java/com/bkanent/listing/rag/ListingRagBackfillService.java:20–31`。
- 记忆入口：`memory-service/src/main/java/com/bkanent/memory/controller/MemoryController.java:170–182`；回填分页/语义：`memory-service/src/main/java/com/bkanent/memory/service/MemoryRagBackfillService.java:15–130`。
- 管理RPC：`common/src/main/java/com/bkanent/common/rpc/RagSpaceAdminRpcService.java:15–19`；能力证据：`rag-service/src/main/java/com/bkanent/rag/config/ManagedRagSpaceRegistry.java:84–126`。
- 最新应用/STALE/replay：`rag-service/src/main/java/com/bkanent/rag/index/RagIndexService.java:95–172`。

源码/本地实现完成与运行门禁分别记录：7.2 以来源执行清理为验收，8.1 以受权、事务锁快照、统一outbox及交错回归为验收；是否已真正部署/回填/切流由相应运行任务验收。权限权威路径也不等于运行账号已授权。任务只按原完整要求勾选，不拆项凑十个、不把本文件的说明或 OpenSpec strict 通过当真实环境验证。
