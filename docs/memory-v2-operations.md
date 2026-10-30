# Memory v2 数据与受管空间运维

A2A/Auth 与 Memory v2 的服务签名身份统一按[服务身份密钥运维](service-identity-operations.md)管理：每服务一组 Ed25519 密钥，私钥只读挂载，verifier 按自己的 ACL 登记公钥。Memory v2 proof 只使用 Ed25519。

## Agent 必需接入

Supervisor 与所有官方 A2A Agent 都必须在模型或业务工具执行前，通过 Memory 完成当前身份绑定、工作集读取与授权/版本复核；成功回合经许可后归档。Memory 接线缺失、服务不可用、认证/授权失败或版本冲突时，入口必须失败关闭，不得以配置开关或原业务路径旁路。成功读取但没有匹配历史是有效空工作集；明确依赖某个历史对象的请求应先澄清。

压缩、偏好/经验加工及 RAG 索引/搜索属于附加能力。其关闭或不可用只能影响对应附加结果，不能关闭基础工作集、当前授权复核或本轮归档。Memory 调用方使用已有服务 Ed25519 私钥；verifier 只配置该服务公钥和精确 ACL，不为 Memory proof 新增共享 secret。

## 数据库升级

Memory v2 的 SQL 升级由 `docker/mysql/init-manifest.tsv` 固定选择，禁止用目录 glob 执行 `sql/migrations/`。清单校验每个 SQL 和 schema check 的 SHA-256；校验按去除 CR 的 LF 文本计算，使 Windows checkout 与 Linux 执行一致。成功后版本、两个 checksum 和应用时间写入各目标库的 `bk_schema_migration`。版本记录已存在时，升级器校验 checksum 并重新运行 schema check；版本记录缺失时只执行该行清单 SQL。

在仓库根目录运行：

```bash
bash scripts/deploy/apply-migrations.sh --dry-run
```

该命令检查整份升级清单的版本顺序、路径和 checksum，不连接 MySQL。实际升级前确认目标容器归属为本机 Compose 项目 `bk-anent-dev`，MySQL 健康，并有可恢复的本机数据库备份；然后在同一终端运行：

```bash
bash scripts/deploy/apply-migrations.sh
```

脚本只使用现有 `bk-anent-dev-mysql-1`，在该容器内部读取 root 环境变量并调用 MySQL 客户端，口令不会进入命令参数。不要对已有数据卷执行 fresh 初始化，也不要删除 volume。运行结束时每个选中版本都必须报告 `Verified`；再次运行同一命令应只复核既有 checksum 和 schema，不重复创建或覆盖数据。

本次 Memory v2 增补的 `210` 至 `240` 均目标为 `bk_memory`：经验版本与证据、结果/反馈收件箱与选择缓存、经独立治理批准的域级经验、RAG outbox 跨实例租约。迁移采用 `CREATE TABLE IF NOT EXISTS` 或检查后变更列/索引；既有对话、偏好、共享授权、RAG 投影/outbox、Agent workflow/checkpoint 和认证用户均不在这些 SQL 的删除或重写路径中。执行前后应核对 `bk_schema_migration` 的 Memory 版本列表，以及 `bk_memory.memory_rag_outbox`、`memory_rag_projection` 和偏好/共享授权表的行数；检查只输出计数，不读取正文。

fresh volume 初始化使用同一 manifest，但会运行 `fresh` 标记的 bootstrap/种子文件。它只适用于全新空 volume；在已有开发数据库上不得用 fresh 模式模拟升级。本机资源受限时不为 fresh 测试额外启动 MySQL 容器，记录此项为未测。

## ES / Milvus 受管空间

Memory v2 的逻辑空间由 `nacos/rag-service.yaml` 注册，覆盖五类记录（session、summary、preference-history、experience、collaboration）与六个精确 domain/agent 组合：`marketing/marketing-agent`、`interview/interview-agent`、`listing/listing-agent`、`analytics/platform-operations-analytics`、`review/platform-operations-review`、`knowledge/platform-operations-knowledge`。每个组合有独立 ES index 和 Milvus collection；物理目标只由受管配置定义。合同、结算、交易、Compare 和已退役的旧记忆空间不属于当前注册面；`docs/acceptance/` 中的历史盘点不能证明当前目标已经 provision 或可用。

逐项操作使用 [私有 RAG / A2A 运行探针](../scripts/verification/README.md)：先以 `DOMAIN / INSPECT` 查询具体逻辑空间，再按获批的目标清单执行 `DOMAIN / PROVISION`。探针要求真实当前 ADMIN token、Memory RAG 的受信 key、现有项目私有 Docker 网络、已 package 的服务 jar 和本机已存在的 JDK 镜像；它不启动 RAG、ES、Milvus 或新的服务容器。对每次 provision 只能传配置所列 schema version 和维度，不接受物理名称。`ready=false` 需要按失败 stage 排查，不得打开 dispatcher 或把未就绪标为成功。

Provision 只准备物理空间。偏好/经验加工、RAG 投影与搜索、dispatcher 可以按治理门禁逐域启停；Supervisor 与官方 Agent 的基础 Memory 读取、授权复核和归档则始终必需，不设消费开关。不得在 Agent 侧绕过 Memory 的授权核验直接搜索私有空间。

## 停止入口与回滚

Memory 是 Agent 执行的必需依赖。发生故障或准备回滚时，先在网关/任务入口停止接收新 Agent 工作；不要关闭 Memory 后继续无记忆运行。保持已受理任务的执行和归档状态可观察，按受控截止时间检查可靠交付与加工水位：

   ```sql
   SELECT state, COUNT(*) FROM bk_agent.agent_memory_delivery GROUP BY state;
   SELECT job_kind, state, COUNT(*) FROM bk_memory.memory_processing_job GROUP BY job_kind, state;
   SELECT state, COUNT(*) FROM bk_memory.memory_rag_outbox GROUP BY state;
   ```

`agent_memory_delivery` 的 `PENDING`、处理 job 的 `PENDING/RETRY/PROCESSING`、以及 RAG outbox 的 `PENDING/WAITING` 或未过期租约表示仍有工作。到截止时间仍未排空时，记录准确状态并保留 journal、job、outbox、tombstone 与 v2 真源；不得清空队列、删表、删集合或恢复已撤回正文。附加偏好/经验加工和 RAG dispatcher 可在其队列按既定规则排空后独立停用；基础 Memory 只能随 Agent 入口一起停用，不能对仍可接收任务的 Agent 关闭。

恢复时部署相互匹配的 Agent/Memory 版本，核对每个调用服务私钥对应的公钥登记和最小 ACL，再验证空工作集、已存在历史、归档回执及 Memory 不可用时的失败关闭行为后恢复入口。未知事件不得重放，已完成业务副作用不得重复执行，偏好历程不得压成旧单值。

历史运行快照（2026-10-22，早期可选接入策略）：本机只读观察到当时 `bk-anent-dev` 运行容器为 Memory、Nacos、MySQL；Memory archive、偏好处理、域投影和域搜索关闭，Supervisor 与领域 A2A 服务未运行。交付与加工 job 为空，RAG outbox 117 项全部为 `APPLIED`。后续只启动 `agent-service` 并复用现有依赖，readiness 返回 `UP`；当时 Agent Memory 与 archive 仍关闭，所以该快照不能证明当前强制接入行为，也未执行真实切流/排空演练。此记录不构成当前运行配置或当前验收结果。

2026-10-22 旧物理存储只读盘点：ES 共 5 个索引，`rag_memory_constraint_v1` 有 17 文档；`rag_memory_preference_v1`、`rag_listing_knowledge_v1`、`marketing_content`、`listing_info` 均为空，ES alias 列表为空。Milvus 默认库共 3 集合，constraint 有 17 行，preference/listing 各 0 行；三者字段均为 `doc_id`、`content`、`metadata`、1024 维 `embedding`，向量索引为 COSINE，collection alias 为空。Nacos 已声明 35 个 Memory v2 逻辑空间并为每个空间绑定 ES index/Milvus collection，但本次旧物理清单未发现这些目标名；当时尚未执行 PROVISION，也未创建空 schema。该轮使用的身份无 ADMIN 权限，因此没有执行管理 RPC；未读取正文或更改存储。盘点结束后已停止 ES、Milvus、etcd 和 Milvus MinIO，只保留原有 Memory、MySQL、Nacos。

2026-10-22 后续受权管理探针验收：只对 `memory.v2.session.interview.interview-agent` 执行 `INSPECT` / `PROVISION`。按受管配置创建 `memory-v2.1`、1024 维空间后，KEYWORD 与 VECTOR 结构检查通过；复查仍为 `ready=false`，唯一未就绪阶段为 `RERANK / DEPENDENCY_UNAVAILABLE`。

2026-10-22 全部受管空间运行态复核：使用真实 ADMIN 对 `nacos/rag-service.yaml` 登记的 35 个 Memory v2 逻辑空间逐个执行 `INSPECT`。34 个空间缺少 KEYWORD 与 VECTOR schema，对其使用受管模板 `memory-v2.1`、1024 维执行 `PROVISION`；返回的复检中两阶段均通过。原有的 `memory.v2.session.interview.interview-agent` 已有两类 schema，没有重复创建。35 个空间均因 `RERANK / DEPENDENCY_UNAVAILABLE` 返回 `ready=false`；没有写入业务记忆、开启 archive/处理/投影/搜索/dispatcher，也没有启动 reranker。探测只使用已有 `bk-anent-dev` 服务，结束后停止本轮启动的 Redis、Auth、RAG、Elasticsearch、Milvus、etcd 和 Milvus MinIO；当时仅原有 Memory、MySQL、Nacos 保持运行。

2026-10-22 Reranker 联机复核：启动既有 `reranker-bge-v2-m3-reranker-1`，未重建镜像；其 health 为 `healthy`。外部项目 `deploy/smoke-test.ps1` 的受控合成请求通过，验证两个候选的原始排序、有限相关度分数和专用 DNS。`rag-service` 与 Reranker 已连接同一专用 internal 网络。随后使用真实 ADMIN 对 35 个 Memory v2 逻辑空间只读执行 `INSPECT`，35/35 均为 `ready=true`、dimension=1024、failedStageCount=0。此次没有执行 `PROVISION`、写入业务记忆或开启 archive/处理/投影/搜索/dispatcher。完成后停止本轮启动的 Redis、Auth、RAG、Elasticsearch、Milvus、etcd 和 Milvus MinIO；当前保留原有 Memory、MySQL、Nacos，以及按请求启动的 Reranker。此时 fresh-volume 初始化尚待后续验收。

2026-10-22 本仓 RAG 客户端联机合成验证：使用已构建的 `rag-service` 可执行包和本仓 `BgeRerankService`、生产受保护 HTTP transport，通过既有 `bkanent-rerank-dev` 网络向健康的 Reranker 提交两个合成候选。治理接口响应的模型 alias/revision/artifact digest/scoring version、LOGIT 分数语义、候选完整性、有限 raw score、归一化分数及 Paris 优先排序均通过，耗时 322 ms。仅启动一个 768 MiB 上限的临时探针运行器，结束后自动删除；没有启动 RAG、ES、Milvus 或其他项目服务容器，没有访问数据库、ADMIN 接口或业务数据。该证据补齐本仓真实客户端到 Reranker 的请求验证；ES 目标仍缺失，KI-57 保持 OPEN。

2026-10-22 空卷首启与幂等复核：使用 `docker/mysql/compose.init-check.yml` 的隔离 MySQL 配置及本轮唯一临时空卷，未发布宿主端口或连接业务网络。官方 entrypoint 首次初始化完成后，manifest 的 24 个 fresh/upgrade 版本（010-240）及 schema checks 全部报告 `Verified`；对同一实例立即重复 `--mode fresh`，再次 24/24 `Verified`。随后按精确 Compose project 标签移除了本轮容器、internal network 和临时卷，现有 `bk-anent-dev` MySQL 数据卷未更改。
