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
