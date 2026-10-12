# Linux 部署环境初始化

本仓库的编排（`docker-compose.yml`）本身跨平台，但此前的构建、预检与启动脚本均为 PowerShell（`scripts/local/`、`scripts/start-mysql-mcp.ps1`），Linux 主机上缺少一套部署环境初始化过程。`scripts/deploy/` 下的脚本补齐这一层：宿主机预检 → `.env` 密钥引导 → 数据卷迁移 → 启动/状态/停止。全部脚本只依赖 bash 与 Docker，不依赖 PowerShell。

## 前置条件

- Linux x86_64（或 arm64），bash 4+；建议 Ubuntu 22.04 / Rocky Linux 9 或同代发行版
- Docker Engine >= 24 且含 Compose V2 插件（`docker compose version` 可用；不支持 docker-compose v1）
- 资源建议：`minimal` 档位 >= 4GB 内存；`full` 档位 >= 16GB 内存、>= 30GB 可用磁盘（ES/Milvus/九个业务服务同机）
- 内核参数：`full` 档位包含 Elasticsearch 与 Milvus，宿主机必须满足 `vm.max_map_count >= 262144`

## 初始化过程

```bash
./scripts/deploy/init-environment.sh --profile full
```

该脚本依次执行：

1. **工件完整性**：确认 `docker-compose.yml`、`Dockerfile`、`sql/mysql-init.sql`、`docker/mysql/004-supervisor.sql`、`nacos/` 就位。
2. **Docker/Compose 校验**（`--skip-docker` 可跳过，用于在无 docker 的中转机上预生成配置）。
3. **内核参数**：`vm.max_map_count` 不达标时给出修复命令；加 `--fix-kernel` 则通过 sudo 修正并持久化到 `/etc/sysctl.d/99-bkagent.conf`。
4. **资源预检**：内存与磁盘只告警不阻断。
5. **`.env` 引导**：不存在则从 `.env.example` 复制；随后仅为**空缺**的密钥项生成随机值（`MYSQL_ROOT_PASSWORD`、`AUTH_TOKEN_SECRET`、MinIO 内外部凭据、MySQL MCP 密码与 Token），**绝不覆盖已有非空值**；文件权限设为 600。`--bind 0.0.0.0` 可把 `HOST_BIND_ADDRESS` 改为对外绑定。
6. **端口冲突检查**：按档位检查宿主机端口（本环境容器已在运行时跳过）。MySQL 默认不在宿主机开 3306，需要时用 `docker-compose.mysql-client.yml` 覆盖。
7. **Nacos 配置完整性**：确认档位所需的 `nacos/*.yaml` 齐全，缺文件会导致 `config-init` 阶段失败。

## RAG 迁移部署与发布门禁

按本项目现有 Dockerfile 模块构建约定执行：`bk-anent-rag:dev` 使用 `MAVEN_MODULES=rag-service` 与 `SERVICE_ARTIFACT=rag-service-1.0.0-SNAPSHOT.jar`。这是沿用开发固定 tag 的规则，不是不可变发布版本；切流前须另行保存实际镜像 ID/digest 与配置版本，禁止套用外部 reranker 项目的镜像规则。

- RAG 是显式 `rag` profile，`start.sh --profile full --with-rag` 才加入；不默认为 full 启动，避免尚未建立的 RAG 控制 DB 阻断结构化业务。RAG 依赖 config-init/MySQL，消费者不依赖 RAG 的 `service_healthy`，agent 不再依赖 Milvus、listing 不再依赖 ES 初始化/readiness；营销保留自身 ES。
- HTTP 管理端口 9018 仅绑定 loopback，Dubbo 20898 仅容器网络发现，QoS 关闭；实施前未发现仓库旧声明冲突，但仍需部署时核对实际宿主/容器占用。本轮无搜索公网发布。
- 控制 DB 为 `MYSQL_DB_RAG` 默认 `bk_rag`，使用独立 `RAG_MYSQL_USER`/`RAG_MYSQL_PASSWORD`。`ragBaseReadiness` 检查安全配置、`rag_index_event`/`rag_document_state` 两表查询权限和至少三个已导出 RPC provider，不直接探测 Nacos 或检索后端；通过仍不证明真实注册发现、空间能力或数据水位。先审查并受权应用 RAG 迁移/权限，不能凭数据库 TCP/healthy 或两表查询证明完整表结构和敏感 payload 权限已满足。既有卷的全量迁移入口仍有 KI-37/38，首启全量结构仍有 KI-39；不要为本次部署顺手修全部历史 SQL。
- `.env` 不由本变更读取或修改。Compose RAG 不继承全量环境锚点；只有 RAG 获得 `RAG_MILVUS_*`、`RAG_EMBEDDING_*`、`RAG_RERANK_*`、`RAG_ELASTICSEARCH_URIS` 和受管 `RAG_HTTP_ALLOWED_HOSTS`。营销单独使用 `MARKETING_ELASTICSEARCH_URIS`，chat 的 `DEEPSEEK_*`/`KE_API_KEY` 保留原业务归属。旧 `AGENT_RAG_RERANK_*`/`MILVUS_*` 不会自动变成新 RAG 配置。
- 签名凭据按服务独立：`RAG_AUTH_RPC_SECRET`、`LISTING_RAG_RPC_SECRET`、`MEMORY_RAG_RPC_SECRET`、`AGENT_RAG_RPC_SECRET` 与受管 auth 响应 `AUTH_RAG_RPC_RESPONSE_*`，默认 secret 为空失败关闭。角色列表为空，agent 知识关闭，领域 dispatcher 与 RAG worker 关闭；没有以通用 ADMIN/用户 ID 推断授权。INSPECT/PROVISION/REPLAY 还必须携带 USER 真实凭据且通过空间 adminRoles，SERVICE writer 即便获 operation grant 也不能管理。
- `nacos/rag-service.yaml` 三个候选空间用 bracket map key 保留点号：`listing.knowledge`、`memory.preference`、`memory.constraint`；物理名字和 1024 维都是待核对模板，无真实 provision、旧身份映射、过滤等价或 ready 证据。未知通用知识空间不注册，重排 scoring profile 缺失时能力关闭。

### 显式配置发布（仅授权后执行）

`config-init` 不再扫描全目录发布 public：必须主动设置 `NACOS_CONFIG_DATA_IDS` 为**本次获授权的空格分隔 YAML data ID 文件名列表**，使用实际 `NACOS_NAMESPACE` 的 namespace ID 作 `tenant`，group 固定 `DEFAULT_GROUP` 与应用 import 一致。未选择 data ID 时退出 64，首次开发启动同样要求主动选择；仅调端口或配置文件存在不等于已发布。`mysql-common.yaml` 无人 import（KI-50），不应作为 RAG 共享配置来源。

更新只允许所选 data ID 与受影响服务；发布会覆盖所选配置，先取得实际内容/版本校验和和回滚快照，不能用历史 README 的“已发布 16 项”证明现在仍生效。严禁以 `start.sh` 全量启动间接无差别覆盖生产。此轮只修改仓库结构，**未执行 config-init、配置 publish、Docker 构建/启动或公网发布**。

### 真实切流阻塞与验收

以下项目尚未满足，因此 tasks 8.2/8.3/8.5/8.8 不得标完成：

1. 当前 namespace/group/data ID 生效内容、consumer/RPC 实际发现与镜像 digest/config 快照均未读取运行态验证；Compose 已为 memory 显式传入同 namespace/DEFAULT_GROUP 的 `DUBBO_REGISTRY_ADDRESS`，并为 listing 传入空默认的 `AGENT_RAG_RPC_SECRET`，但模板接线和类路径上的 Dubbo（KI-53）不等于真实 RPC 发现/鉴权已通过。KI-51 memory 库名已改占位，运行态仍需核对。
2. 合规真实 HTTP 后端目标尚缺：现有内网 ES、`host.docker.internal:18119/rerank` 均不符合本变更策略；不能加内网豁免、公开旧服务或擅自换目标。空 allowlist 不限制公网 hostname，但不豁免私网/保留地址；公网域名也须实际 DNS/每次 HTTP 连接守卫验证。Milvus gRPC 不属于该 HTTP transport，不能声称已覆盖每次 gRPC 重连。JNDI DNS 路径还须验证实际 JRE 具备 `java.naming`/`jdk.naming.dns`；Dockerfile 沿用完整 Temurin 17 JRE，此轮未构建镜像或实测容器 DNS。
3. 真实 owner/权限真源、集合/ES schema、旧文档 ID/PK、模型实测维度、完整投影、两路过滤等价、更新/删除/partial 写补偿、默认双路+重排能力均未验收。单路、stub、TCP/running 和基础 readiness 均不能替代。
4. 领域 outbox/backfill/持久删除水位、旧 writer 和在途 RPC 排空、增量追平、受权短暂停写与消费者切流未执行；未过安全/身份/数据/能力 gate 不能恢复写入口。
5. 官方 A2A SDK `0.2.5.Beta2` 尚无已验证的请求 header 凭据接入 API，真实跨域身份传播继续阻塞，不能把 token 放 metadata、改 provider 协议或暗加第三传输。
6. 一个兼容发布周期与消费者升级证明尚缺；旧索引 RPC 清理不能提前。按事件水位恢复新兼容版本或向旧版重放差异的回滚演练尚缺；不是只改路由。全部索引、集合和数据卷保留。

KI-42 的等待/状态成功误报、KI-48 的 Redis readiness 与真实客户端不一致继续列门禁，不借此关闭无关依赖或声称全量环境验证完成。真实验收须由实施者在获授权且合规目标具备后执行：携带真实 USER 管理凭据调用 common 的 `RagSpaceAdminRpcService.inspect/provision`，通过领域检索入口发送缺省双路/重排请求，并核对 requestedMode/effectiveMode、scoreKind/scoringVersion；按相同 eventId 调用 `RagIndexRpcService.getStatus` 核对 APPLIED 及更新/删除补偿。没有额外 RAG 公网管理 HTTP 端点。诊断脚本为 `./scripts/deploy/status.sh`，但其退出值不能替代上述能力验收。授权反例、水位追平及恢复演练逐项留证，失败或未执行保留非零/阻塞事实。

## 数据卷：全新 vs 已有

- **全新数据卷**：MySQL 首次初始化自动执行 `docker-entrypoint-initdb.d` 中的 `sql/mysql-init.sql`、两份 interview 脚本和 `docker/mysql/004-supervisor.sql`（后者内部 `SOURCE` 两份 supervisor 迁移），无需手工迁移。
- **已有数据卷**：初始化不会重跑，须先应用增量迁移：

```bash
./scripts/deploy/apply-migrations.sh            # 全部，按文件名顺序，逐个确认
./scripts/deploy/apply-migrations.sh --dry-run  # 只看清单
./scripts/deploy/apply-migrations.sh --file sql/migrations/20261004_supervisor_run_control.sql --yes
```

迁移文件不保证幂等（部分是不带 `IF NOT EXISTS` 的 `ALTER TABLE`）：重复应用会报重复列/表错误，这通常说明该文件已应用过，跳过即可。与 [Supervisor 部署说明](supervisor-orchestration-deployment.md)的口径一致：已有卷的迁移是运维动作，不依赖容器首启。

## 启动 / 状态 / 停止

```bash
./scripts/deploy/start.sh --profile minimal            # 最小链路：MySQL+Nacos+配置导入+认证+网关
./scripts/deploy/start.sh --profile full --with-mcp    # 全量 + MySQL MCP；首次启动含容器内 Maven 构建，耗时较长
./scripts/deploy/status.sh                             # 容器状态 + HTTP 健康探测
./scripts/deploy/stop.sh                               # 停止并保留容器与数据卷
./scripts/deploy/stop.sh --down                        # 连容器一起删除（数据卷保留）
./scripts/deploy/stop.sh --down-volumes --yes          # 连数据卷一起销毁（必须显式 --yes）
```

`start.sh` 提交启动并等待容器状态，超时默认 600 秒（`--wait-timeout` 可调）。既有等待/状态层存在 KI-42 的成功误报限制，不能以脚本退出 0 或 TCP/running 证明所有预期服务、配置和 RAG 能力就绪。验证入口与 README「Docker 开发部署」一致：网关 `http://127.0.0.1:5010/gateway/health`，认证 `http://127.0.0.1:9101/auth/health`，Nacos 控制台 `http://127.0.0.1:18080/`。

## 裸机分布式启动

不使用 Docker 直接 `mvn spring-boot:run` 启动分布式 profile 前，运行基础设施可达性检查（对应 `check-environment.ps1 -Mode distributed` 的 Linux 版）：

```bash
./scripts/deploy/check-environment.sh   # 从 .env 读取 Nacos/MySQL/Redis/RocketMQ/Milvus/MinIO/ES 地址并探测端口
```

## 安全基线

- `.env` 含真实密钥：权限 600、已被 `.gitignore` 与 `.dockerignore` 排除，禁止提交或打进镜像。
- Compose 默认只绑定 `127.0.0.1`；`--bind 0.0.0.0` 对外开放前确认防火墙/安全组，并同步复查 `A2A_PUBLIC_BASE_URL`、`MINIO_PUBLIC_BASE_URL`（不能用对端不可达的 loopback 地址）。
- 真实模型调用必须替换占位密钥：`DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY`、`AGENT_RAG_RERANK_API_KEY`（compose 中的占位值只保证容器可启动）。
- `.env` 只放端口、基础设施连接、密钥及启动引导参数；集成模式、Token TTL、模型参数、Redis 排行榜、流式 provider 和 Milvus/ES 搜索开关均在对应 Nacos data ID 中维护，不再放入 `.env`。
- 分布式生产部署必须在 Nacos 将 `contract.integration.mode`、`notification.integration.mode`、`media.integration.mode`、`promotion.integration.mode` 显式设为 `real`，并接入/选择真实 provider（合同同时配置真实 OCR/电子签 provider）；仅改 `real` 而没有真实实现仍会报告未实现，不会伪造成功。仓库 YAML 默认保持 Docker 开发模式 `local`，导入前准备环境专属配置；`config-init` 只发布显式选择的 data ID 到实际 namespace，默认不发布；它仍会覆盖所选配置，不能用开发模板覆盖生产设置。依赖 readiness 保持不变。
- Compose 已允许 `.env` 中的 `NACOS_USERNAME`、`NACOS_PASSWORD`、`MINIO_PUBLIC_BASE_URL` 覆盖应用容器取值。Nacos Admin 账号初始化及目标命名空间 Card 更新权限须另行配置，设置或生成密码并不代表已有授权（LR-28）。
- 旧库 `user_account.password_hash` 为明文的，先按 README 完成 BCrypt 迁移再开放认证。
- 初始化随机生成的 `MYSQL_MCP_PASSWORD`/`MYSQL_MCP_TOKEN` 已就位，启用 MCP 只读服务：`docker compose --profile mcp run --rm mysql-mcp-init` 后 `./scripts/deploy/start.sh --profile mcp`。

## 常见问题

- **Elasticsearch/Milvus 起不来**：`sysctl -n vm.max_map_count` 低于 262144；按上文修复或用 `--fix-kernel`。
- **端口冲突**：释放端口或改 `.env` 中对应 `*_EXPOSED_PORT`；容器已运行时 init 会自动跳过该检查。
- **full 首次启动很慢**：镜像构建在容器内执行 Maven 打包；重复启动加 `--no-build`。
- **config-init 上传失败**：Nacos 未就绪时任务自带重试；配置文件缺失属初始化校验范围，init 阶段即会报错。
- **内存不足**：降级用 `minimal` 档位，或拆分基础设施到独立主机。裸机工具仍从 `.env` 的基础设施地址读取；Compose 容器内默认使用服务名连接，外置基础设施需显式调整容器连接配置，不能仅改宿主机 `*_HOST` 就认为已生效。

## 关联

- [Supervisor 工具循环部署](supervisor-orchestration-deployment.md)：九服务 + Supervisor 的发布顺序与运行面约束
- [README「分布式环境启动」](../README.md)：配置约定、集成模式与 `.env` 变量清单
