# Memory v2 数据与受管空间运维

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

Memory v2 的逻辑空间由 `nacos/rag-service.yaml` 注册，覆盖五类记录（session、summary、preference-history、experience、collaboration）与七个精确 domain/agent 组合（marketing、interview、listing、trade、contract、compare、settlement）。每个组合有独立 ES index 和 Milvus collection；物理目标只由受管配置定义。`docs/acceptance/` 中的历史迁移盘点只描述旧 RAG 代际，不能证明这些 v2 目标已经 provision 或可用。

逐项操作使用 [私有 RAG / A2A 运行探针](../scripts/verification/README.md)：先以 `DOMAIN / INSPECT` 查询具体逻辑空间，再按获批的目标清单执行 `DOMAIN / PROVISION`。探针要求真实当前 ADMIN token、Memory RAG 的受信 key、现有项目私有 Docker 网络、已 package 的服务 jar 和本机已存在的 JDK 镜像；它不启动 RAG、ES、Milvus 或新的服务容器。对每次 provision 只能传配置所列 schema version 和维度，不接受物理名称。`ready=false` 需要按失败 stage 排查，不得打开 dispatcher 或把未就绪标为成功。

Provision、索引投影和读取是独立开关。新空间准备不等于开启 memory archive、经验/偏好处理、Supervisor prompt read、RAG dispatcher 或业务消费者；默认关闭值保持关闭，只有相应迁移和治理 fixture 通过后，才按逐域验收单独启用。不得在 Agent 侧绕过 Memory 的授权核验直接搜索私有空间。

## 分阶段停用与回滚

每个 A2A 消费服务使用 `a2a.memory.enabled` 主开关与两个独立运行开关：`a2a.memory.read-enabled` 控制是否把获准历史装入模型上下文，`a2a.memory.delivery-enabled` 控制本轮结果是否交付到 Memory。Supervisor 对应 `agent.memory-v2.enabled`、`agent.memory-v2.read-enabled` 和 `agent.memory-v2.delivery-enabled`。读取与交付开关位于各服务 Nacos 配置中；发布时确认服务已收到配置刷新。主开关在切流前保持开启。

`read-enabled=false, delivery-enabled=true` 是排空模式。新 A2A 任务不加载历史内容，模型结束后才建立写入会话并归档当前结果；Supervisor 新任务使用 `DELIVERY_ONLY`，不向模型添加 Memory 历史，在任务完成时交付执行消息。已接受任务会记录其交付许可，即使随后关闭交付开关也会尝试完成该次交付。排空模式仍需读取 Memory 的当前版本以生成正确序号，但只在结果归档阶段读取，不把历史正文放入模型上下文。

按以下顺序回滚：

1. 先让网关或当前任务入口停止接收新的 Agent 工作，保持服务进程可用；不要直接停止正在处理任务的容器。
2. 在各实际启用域和 Supervisor 配置中将 `read-enabled` 设为 `false`，保持 `delivery-enabled=true`；保持 Memory archive 接收端、偏好/经验 worker 与 RAG dispatcher 运行。确认配置刷新后，检查新任务没有加载 Memory 历史。
3. 等待所有已接收 Agent 任务结束，然后将各域 `delivery-enabled` 设为 `false`。这一步之后新任务不会创建记忆交付，已接受任务仍可提交其交付。
4. 在受控截止时间内检查交付与加工水位：

   ```sql
   SELECT state, COUNT(*) FROM bk_agent.agent_memory_delivery GROUP BY state;
   SELECT job_kind, state, COUNT(*) FROM bk_memory.memory_processing_job GROUP BY job_kind, state;
   SELECT state, COUNT(*) FROM bk_memory.memory_rag_outbox GROUP BY state;
   ```

   `agent_memory_delivery` 的 `PENDING`、处理 job 的 `PENDING/RETRY/PROCESSING`、以及 RAG outbox 的 `PENDING/WAITING` 或未过期租约表示仍有工作。到截止时间仍未排空时，记录准确状态并保留 journal、job、outbox、tombstone 与 v2 真源；不得清空队列、删表、删集合或恢复已撤回正文。
5. 只有交付与待加工队列清零后，才关闭偏好/经验加工和 RAG dispatcher。之后关闭各领域与 Supervisor 的主开关，并关闭 Memory archive 接收端。Memory 服务的 archive/worker 开关由 Compose 环境变量提供；更改 `.env` 后只在入口已停止且无在途 RPC 时更新现有 Memory 服务。无需创建测试容器。
6. 切回原业务调用路径前，确认旧路径可用，v2 的当前授权、撤回状态与所有历程仍保留。恢复新链路时按当前版本重新执行配置与水位检查，不回放未知事件，不把完整偏好历程压成旧单值。

本机 2026-10-22 回滚预检只读观察到 `bk-anent-dev` 的运行容器为 Memory、Nacos、MySQL；Memory archive、偏好处理、域投影和域搜索均为关闭，经验开关未设置且按默认关闭，Supervisor 与领域 A2A 服务未运行。`agent_memory_delivery`、`memory_processing_job` 和经验事件收件表均为空；RAG outbox 117 项全部为 `APPLIED`。后续最小联机复核按 Compose 默认设置重建共享服务镜像（23 个模块打包成功），但只启动 `agent-service`，复用 Memory、MySQL、Nacos，另启动其依赖 Redis 与 RocketMQ NameServer；将 `agent-service.yaml` 单独发布到本机 Nacos 并读回校验，替换后的 Agent readiness 返回 `UP`。Agent Memory 与 Memory archive、加工、域投影/搜索、RAG dispatcher 仍关闭，领域 A2A 消费者保持停止，没有真实入口或待排空工作，因此不能执行真实切流/排空演练，也不把静态关闭状态记作演练通过。

2026-10-22 旧物理存储只读盘点：ES 共 5 个索引，`rag_memory_constraint_v1` 有 17 文档；`rag_memory_preference_v1`、`rag_listing_knowledge_v1`、`marketing_content`、`listing_info` 均为空，ES alias 列表为空。Milvus 默认库共 3 集合，constraint 有 17 行，preference/listing 各 0 行；三者字段均为 `doc_id`、`content`、`metadata`、1024 维 `embedding`，向量索引为 COSINE，collection alias 为空。Nacos 已声明 35 个 Memory v2 逻辑空间并为每个空间绑定 ES index/Milvus collection，但本次旧物理清单未发现这些目标名；没有执行 PROVISION，未创建空 schema。仓库运行探针要求当前 ADMIN 才能执行 RAG INSPECT；本机提供身份的 roleCode 为 BROKER，且 `.env` 未配置专用 `RAG_PROBE_ACCESS_TOKEN`，因此未执行管理 RPC。未读取正文或更改存储。盘点结束后已停止 ES、Milvus、etcd 和 Milvus MinIO，只保留原有 Memory、MySQL、Nacos。
